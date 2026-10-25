package dev.kairos.state.settle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.state.settle.application.WorkflowResultHandler;
import dev.kairos.state.stats.application.StatsBuffer;
import dev.kairos.state.stats.infrastructure.JdbcStatsWriter;
import dev.kairos.state.stats.infrastructure.StatsAggregator;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@KairosIntegrationTest(kafka = false, redis = false)
class StatsAggregationIntegrationTest {
    private SettlementFixtures f;

    @BeforeEach
    void setup(KairosPostgres postgres) {
        f = new SettlementFixtures(postgres);
    }

    private StatsAggregator aggregator(String node) {
        var meters = new SimpleMeterRegistry();
        return new StatsAggregator(
                node,
                new StatsBuffer(f.clock, shard -> f.cells.forShard(shard).id(), 100000, meters),
                new JdbcStatsWriter(f.cells),
                meters);
    }

    private dev.kairos.state.settle.infrastructure.JdbcResultSettler settler(StatsAggregator aggregator) {
        return f.settler(WorkflowResultHandler.pending(), List.of(aggregator));
    }

    private long sum(String table, String column) {
        return f.cells.allCells().stream()
                .mapToLong(cell -> cell.jdbc()
                        .sql("SELECT COALESCE(sum(" + column + "),0) FROM scheduler." + table + " WHERE team_id=:team")
                        .param("team", f.team)
                        .query(Long.class)
                        .single())
                .sum();
    }

    @Test
    void twoReplicasAcrossBothCellsFlushExactTotalsDespiteBatchReplayAndRepeatedFlushes() throws Exception {
        var a = aggregator("state-a-" + f.team);
        var b = aggregator("state-b-" + f.team);
        UUID first = f.job(12), second = f.job(140);
        var left = new ArrayList<dev.kairos.proto.v1.Result>();
        var right = new ArrayList<dev.kairos.proto.v1.Result>();
        for (int i = 0; i < 100; i++) {
            UUID run = f.run(i % 4 < 2 ? first : second);
            var result = f.result(run, i % 4 == 0 ? 2 : 1, i % 4 == 0 ? 11 : 0, 0).toBuilder()
                    .setLatencyMs(i + 1)
                    .build();
            (i % 2 == 0 ? left : right).add(result);
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> {
                settler(a).settle(left);
                settler(a).settle(left);
                a.flush();
                a.flush();
            });
            var two = executor.submit(() -> {
                settler(b).settle(right);
                settler(b).settle(right);
                b.flush();
                b.flush();
            });
            one.get(30, TimeUnit.SECONDS);
            two.get(30, TimeUnit.SECONDS);
        }
        for (String table : List.of("team_stats_minute", "endpoint_health_minute")) {
            assertThat(sum(table, "attempts")).isEqualTo(100);
            assertThat(sum(table, "successes")).isEqualTo(75);
            assertThat(sum(table, "dead_lettered")).isEqualTo(25);
            assertThat(sum(table, "latency_sum_ms")).isEqualTo(5050);
        }
        assertThat(sum("endpoint_health_minute", "non_retryable")).isEqualTo(25);
        assertThat(sum("endpoint_health_minute", "retryable")).isZero();
        long nodes = f.cells.allCells().stream()
                .mapToLong(cell -> cell.jdbc()
                        .sql("SELECT count(DISTINCT node_id) FROM scheduler.team_stats_minute WHERE team_id=:team")
                        .param("team", f.team)
                        .query(Long.class)
                        .single())
                .sum();
        assertThat(nodes).isEqualTo(4);
        UUID third = f.run(first);
        settler(a)
                .settle(List.of(
                        f.result(third, 1, 0, 200).toBuilder().setLatencyMs(123).build()));
        a.flush();
        assertThat(sum("team_stats_minute", "attempts")).isEqualTo(101);
        assertThat(sum("endpoint_health_minute", "latency_max_ms")).isEqualTo(420);
    }

    @Test
    void flushAfterAmbiguousDatabaseCommitAndOutOfOrderSnapshotsDoesNotDoubleCountOrRegress() {
        var meters = new SimpleMeterRegistry();
        var buffer = new StatsBuffer(f.clock, shard -> f.cells.forShard(shard).id(), 1000, meters);
        var writer = new JdbcStatsWriter(f.cells);
        String node = "ambiguous-" + f.team;
        UUID job = f.job(12), run = f.run(job);
        var result = f.result(run, 1, 0, 200);
        f.settler(WorkflowResultHandler.pending(), List.of(buffer::record)).settle(List.of(result));
        var older = buffer.snapshot();
        writer.write(node, older);
        writer.write(node, older); // The first commit succeeded, but the caller did not receive its acknowledgement.
        UUID next = f.run(job);
        f.settler(WorkflowResultHandler.pending(), List.of(buffer::record)).settle(List.of(f.result(next, 1, 0, 200)));
        var newer = buffer.snapshot();
        writer.write(node, newer);
        writer.write(node, older);
        writer.write(node, newer);
        buffer.acknowledged(newer);
        assertThat(buffer.snapshot()).isEmpty();
        assertThat(sum("team_stats_minute", "attempts")).isEqualTo(2);
        assertThat(sum("endpoint_health_minute", "attempts")).isEqualTo(2);
    }

    @Test
    void endpointOverridesAndRetryBudgetExhaustionKeepResolvedFailureClassification() {
        f.endpointSpec("{\"statusOverrides\":{\"503\":\"FAIL\",\"400\":\"RETRY\"}}");
        var aggregate = aggregator("classification-" + f.team);
        var settlement = settler(aggregate);
        UUID job = f.job(12);
        UUID dead = f.run(job),
                retry = f.run(job),
                unknown = f.run(job),
                cancelled = f.run(job),
                skipped = f.run(job),
                deferred = f.run(job);
        settlement.settle(List.of(
                f.result(dead, 2, 7, 503),
                f.result(retry, 2, 5, 400),
                f.result(unknown, 3, 8, 0),
                f.result(cancelled, 4, 0, 0),
                f.result(skipped, 5, 0, 0),
                f.result(deferred, 6, 0, 0)));
        aggregate.flush();
        assertThat(sum("endpoint_health_minute", "attempts")).isEqualTo(5);
        assertThat(sum("endpoint_health_minute", "retryable")).isEqualTo(2);
        assertThat(sum("endpoint_health_minute", "non_retryable")).isEqualTo(1);
        f.endpointSpec("{}");
        UUID exhausted = f.run(job);
        f.cell(exhausted)
                .jdbc()
                .sql("UPDATE scheduler.run_inflight SET attempt=6 WHERE run_id=:id")
                .param("id", exhausted)
                .update();
        settlement.settle(List.of(f.result(exhausted, 2, 7, 503)));
        aggregate.flush();
        assertThat(sum("endpoint_health_minute", "retryable")).isEqualTo(3);
        assertThat(sum("endpoint_health_minute", "dead_lettered")).isEqualTo(2);
        f.endpointSpec("{\"unknownOutcomePolicy\":\"REVIEW\"}");
        UUID review = f.run(job);
        settlement.settle(List.of(f.result(review, 3, 8, 0)));
        aggregate.flush();
        assertThat(sum("endpoint_health_minute", "non_retryable")).isEqualTo(2);
    }

    @Test
    void staleAndCancelledResultsAndRolledBackCellDoNotLeakCounts() {
        var aggregate = aggregator("rollback-" + f.team);
        UUID job = f.job(12), run = f.run(job);
        var result = f.result(run, 1, 0, 200);
        settler(aggregate).settle(List.of(result.toBuilder().setFence(1).build()));
        assertThat(aggregate.flush()).isZero();
        // A task's callback failure rolls back all accepted results in the same cell before observers see them.
        UUID task = f.run(job),
                workflowRun =
                        f.ids.newWorkflowRunId(dev.kairos.domain.shard.Shard.of(12))
                                .value();
        f.cell(task)
                .jdbc()
                .sql(
                        "INSERT INTO scheduler.workflow_runs(workflow_run_id,workflow_id,workflow_version,team_id,shard_id,scheduled_at,trigger,total) VALUES (:id,:workflow,1,:team,12,:at,1,1)")
                .param("id", workflowRun)
                .param("workflow", job)
                .param("team", f.team)
                .param("at", f.now())
                .update();
        f.cell(task)
                .jdbc()
                .sql(
                        "UPDATE scheduler.run_inflight SET target_type=2,target_id=:target,task_key='task' WHERE run_id=:id")
                .param("target", workflowRun)
                .param("id", task)
                .update();
        var failed = f.settler(WorkflowResultHandler.pending(), List.of(aggregate));
        assertThatThrownBy(() -> failed.settle(List.of(result, f.result(task, 1, 0, 200))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(aggregate.flush()).isZero();
        assertThat(sum("team_stats_minute", "attempts")).isZero();
        settler(aggregate).settle(List.of(result));
        aggregate.flush();
        assertThat(sum("team_stats_minute", "attempts")).isEqualTo(1);
    }

    @Test
    void partialCellCommitEmitsOnlyCommittedStatisticsAndReplayFinishesOtherCell() {
        var aggregate = aggregator("partial-" + f.team);
        UUID first = f.run(f.job(12)), second = f.run(f.job(140));
        var left = f.result(first, 1, 0, 200);
        var right = f.result(second, 1, 0, 200);
        // Break only the second cell's policy lookup; the first cell remains committed and observable.
        f.cell(second)
                .jdbc()
                .sql("UPDATE scheduler.run_inflight SET endpoint_version=2 WHERE run_id=:id")
                .param("id", second)
                .update();
        assertThatThrownBy(() -> settler(aggregate).settle(List.of(right, left)))
                .isInstanceOf(RuntimeException.class);
        aggregate.flush();
        assertThat(sum("team_stats_minute", "attempts")).isEqualTo(1);
        f.cell(second)
                .jdbc()
                .sql("UPDATE scheduler.run_inflight SET endpoint_version=1 WHERE run_id=:id")
                .param("id", second)
                .update();
        settler(aggregate).settle(List.of(left, right));
        aggregate.flush();
        assertThat(sum("team_stats_minute", "attempts")).isEqualTo(2);
        assertThat(sum("endpoint_health_minute", "attempts")).isEqualTo(2);
    }

    @Test
    void failedEndpointAndTeamFlushRollsBackAsOneCellTransaction() {
        var writer = new JdbcStatsWriter(f.cells);
        UUID job = f.job(12);
        var minute = f.clock.instant();
        var endpoint = new StatsBuffer.Snapshot(
                new StatsBuffer.Key(f.cell(job).id(), f.team, f.endpoint, minute),
                new StatsBuffer.Counts(1, 1, 0, 0, 0, 10, 10));
        var invalidTeam = new StatsBuffer.Snapshot(
                new StatsBuffer.Key(f.cell(job).id(), f.team, null, minute),
                new StatsBuffer.Counts(-1, 0, 0, 0, 0, 0, 0));
        assertThatThrownBy(() -> writer.write("atomic-" + f.team, List.of(endpoint, invalidTeam)))
                .isInstanceOf(RuntimeException.class);
        assertThat(sum("endpoint_health_minute", "attempts")).isZero();
        assertThat(sum("team_stats_minute", "attempts")).isZero();
    }

    @Test
    void partiallyCommittedStatisticsFlushReplaysBothCellsWithoutDoubleCounting() {
        var writer = new JdbcStatsWriter(f.cells);
        String node = "partial-flush-" + f.team;
        var counts = new StatsBuffer.Counts(1, 1, 0, 0, 0, 10, 10);
        var endpoint0 = new StatsBuffer.Snapshot(
                new StatsBuffer.Key(f.cells.forShard(12).id(), f.team, f.endpoint, f.clock.instant()), counts);
        var team0 = new StatsBuffer.Snapshot(
                new StatsBuffer.Key(f.cells.forShard(12).id(), f.team, null, f.clock.instant()), counts);
        var endpoint1 = new StatsBuffer.Snapshot(
                new StatsBuffer.Key(f.cells.forShard(140).id(), f.team, f.endpoint, f.clock.instant()), counts);
        var team1key = new StatsBuffer.Key(f.cells.forShard(140).id(), f.team, null, f.clock.instant());
        var invalid = new StatsBuffer.Snapshot(team1key, new StatsBuffer.Counts(-1, 0, 0, 0, 0, 0, 0));
        assertThatThrownBy(() -> writer.write(node, List.of(endpoint0, team0, endpoint1, invalid)))
                .isInstanceOf(RuntimeException.class);
        assertThat(sum("endpoint_health_minute", "attempts")).isEqualTo(1);
        assertThat(sum("team_stats_minute", "attempts")).isEqualTo(1);
        writer.write(node, List.of(endpoint0, team0, endpoint1, new StatsBuffer.Snapshot(team1key, counts)));
        assertThat(sum("endpoint_health_minute", "attempts")).isEqualTo(2);
        assertThat(sum("team_stats_minute", "attempts")).isEqualTo(2);
    }

    @Test
    void settlementMinuteHandlesOldResultsAndProcessRestartCreatesIndependentRows() {
        UUID run = f.run(f.job(12));
        var result = f.result(run, 1, 0, 200);
        f.clock.advance(Duration.ofMinutes(30));
        var a = aggregator(StatsAggregator.nodeId("pod", f.clock));
        settler(a).settle(List.of(result));
        a.flush();
        assertThat(f.cell(run)
                        .jdbc()
                        .sql("SELECT minute=:now FROM scheduler.team_stats_minute WHERE team_id=:team")
                        .param("now", f.now())
                        .param("team", f.team)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        var b = aggregator(StatsAggregator.nodeId("pod", f.clock));
        assertThat(a.nodeId()).isNotEqualTo(b.nodeId());
        UUID next = f.run(f.job(12));
        settler(b).settle(List.of(f.result(next, 1, 0, 200)));
        b.flush();
        assertThat(sum("team_stats_minute", "attempts")).isEqualTo(2);
    }
}
