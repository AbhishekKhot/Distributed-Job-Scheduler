package dev.kairos.state.settle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.model.Outcome;
import dev.kairos.proto.v1.Completed;
import dev.kairos.state.settle.infrastructure.SettlementAuditRelay;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@KairosIntegrationTest(kafka = false, redis = false)
class ResultSettlementIntegrationTest {
    private SettlementFixtures f;

    @BeforeEach
    void setup(KairosPostgres postgres) {
        f = new SettlementFixtures(postgres);
    }

    @Test
    void successAndReplayAreAtomicAndEmitOnlyOptedInCompletion() throws Exception {
        f.control
                .sql("UPDATE scheduler.teams SET completed_events_enabled=true WHERE team_id=:id")
                .param("id", f.team)
                .update();
        UUID job = f.job(12), run = f.run(job);
        var result = f.result(run, 1, 0, 204);
        var settler = f.settler();
        settler.settle(List.of(result, result));
        settler.settle(List.of(result));
        assertThat(f.count(run, "run_inflight")).isZero();
        assertThat(f.count(run, "run_history")).isEqualTo(1);
        assertThat(f.count(run, "outbox")).isEqualTo(1);
        assertThat(f.value(job, "job_health", "recent_count", "job_id")).isEqualTo(1);
        assertThat(f.observed).hasSize(1);
        byte[] payload = f.cell(run)
                .jdbc()
                .sql("SELECT payload FROM scheduler.outbox WHERE msg_key=uuid_send(:id) AND topic=5")
                .param("id", job)
                .query(byte[].class)
                .single();
        assertThat(Completed.parseFrom(payload).getOutcomeValue()).isEqualTo(1);
        UUID quiet = f.run(job);
        f.control
                .sql("UPDATE scheduler.teams SET completed_events_enabled=false WHERE team_id=:id")
                .param("id", f.team)
                .update();
        f.settler().settle(List.of(f.result(quiet, 1, 0, 200)));
        assertThat(f.count(quiet, "outbox")).isZero();
    }

    @Test
    void staleFenceAndMismatchedCountersCannotSettleCurrentOwner() {
        UUID run = f.run(f.job(12));
        var valid = f.result(run, 1, 0, 200);
        f.settler()
                .settle(List.of(
                        valid.toBuilder().setFence(1).build(),
                        valid.toBuilder().setFence(3).build(),
                        valid.toBuilder().setAttempt(2).build(),
                        valid.toBuilder().setInfraAttempt(1).build()));
        assertThat(f.count(run, "run_history")).isZero();
        assertThat(f.count(run, "run_inflight")).isEqualTo(1);
        f.cell(run)
                .jdbc()
                .sql("UPDATE scheduler.run_inflight SET state=2 WHERE run_id=:id")
                .param("id", run)
                .update();
        f.settler().settle(List.of(valid));
        assertThat(f.observed).isEmpty();
    }

    @Test
    void sixFailuresExhaustDefaultPolicyInAboutSixteenMinutes() {
        UUID run = f.run(f.job(12));
        var start = f.clock.instant();
        var settler = f.settler();
        for (int attempt = 1; attempt <= 6; attempt++) {
            var result = f.result(run, 2, 7, 503);
            int fence = result.getFence();
            settler.settle(List.of(result));
            settler.settle(List.of(result));
            if (attempt < 6) {
                assertThat(f.value(run, "run_inflight", "attempt", "run_id")).isEqualTo(attempt + 1);
                assertThat(f.value(run, "run_inflight", "fence", "run_id")).isEqualTo(fence + 1);
                f.claimRetry(run);
            } else assertThat(f.value(run, "dead_letters", "fence", "run_id")).isEqualTo(fence);
        }
        assertThat(Duration.between(start, f.clock.instant()).toSeconds()).isBetween(930L, 1005L);
        assertThat(f.count(run, "run_history")).isEqualTo(6);
        assertThat(f.count(run, "run_inflight")).isZero();
        assertThat(f.count(run, "dead_letters")).isEqualTo(1);
        assertThat(f.count(run, "outbox")).isEqualTo(1);
        assertThat(f.cell(run)
                        .jdbc()
                        .sql("SELECT count(*) FROM scheduler.run_history WHERE run_id=:id AND final")
                        .param("id", run)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"2,5,400,3", "2,11,0,3", "2,12,0,3", "2,7,503,2", "3,8,0,2"})
    void outcomeClassification(int outcome, int error, int http, int expected) {
        UUID run = f.run(f.job(12));
        f.settler().settle(List.of(f.result(run, outcome, error, http)));
        assertThat(f.value(run, "run_history", "outcome", "run_id")).isEqualTo(expected);
    }

    @Test
    void statusOverridesAndUnknownReviewUsePinnedEndpoint() {
        f.endpointSpec(
                "{\"statusOverrides\":{\"503\":\"FAIL\",\"400\":\"RETRY\"},\"unknownOutcomePolicy\":\"REVIEW\"}");
        UUID job = f.job(12), dead = f.run(job), retry = f.run(job), unknown = f.run(job);
        f.control
                .sql("UPDATE scheduler.api_endpoints SET version=2,spec='{}' WHERE endpoint_id=:id")
                .param("id", f.endpoint)
                .update();
        f.settler().settle(List.of(f.result(dead, 2, 7, 503), f.result(retry, 2, 5, 400), f.result(unknown, 3, 8, 0)));
        assertThat(f.count(dead, "dead_letters")).isEqualTo(1);
        assertThat(f.value(retry, "run_inflight", "attempt", "run_id")).isEqualTo(2);
        assertThat(f.value(unknown, "dead_letters", "reason", "run_id")).isEqualTo(8);
    }

    @Test
    void jobPolicyWinsAndMaximumAgeWinsOverRetryAfter() {
        f.endpointSpec(
                "{\"retryPolicy\":{\"maxRetries\":0,\"strategy\":\"EXPONENTIAL_JITTER\",\"baseDelaySec\":30,\"maxDelaySec\":600,\"maxAgeSec\":86400}}");
        UUID job = f.job(
                12,
                "{\"schedule\":{\"type\":\"INTERVAL\"},\"retryPolicy\":{\"maxRetries\":5,\"strategy\":\"EXPONENTIAL_JITTER\",\"baseDelaySec\":30,\"maxDelaySec\":600,\"maxAgeSec\":60}}");
        UUID run = f.run(job);
        f.settler()
                .settle(List.of(f.result(run, 2, 7, 503).toBuilder()
                        .setRetryAfterMs(100000)
                        .build()));
        assertThat(f.value(run, "dead_letters", "reason", "run_id")).isEqualTo(13);
        UUID second = f.run(job);
        f.settler().settle(List.of(f.result(second, 2, 7, 503)));
        assertThat(f.value(second, "run_inflight", "attempt", "run_id")).isEqualTo(2);
    }

    @Test
    void deferredPreservesAttemptAndInfrastructureBudgetWithoutHistory() {
        UUID run = f.run(f.job(12));
        f.cell(run)
                .jdbc()
                .sql("UPDATE scheduler.run_inflight SET infra_attempt=2,deferrals=4 WHERE run_id=:id")
                .param("id", run)
                .update();
        var result = f.result(run, 6, 0, 0).toBuilder().setRetryAfterMs(5000).build();
        var settler = f.settler();
        settler.settle(List.of(result));
        settler.settle(List.of(result));
        assertThat(f.count(run, "run_history")).isZero();
        assertThat(f.value(run, "run_inflight", "state", "run_id")).isEqualTo(4);
        assertThat(f.value(run, "run_inflight", "attempt", "run_id")).isEqualTo(1);
        assertThat(f.value(run, "run_inflight", "infra_attempt", "run_id")).isEqualTo(2);
        assertThat(f.value(run, "run_inflight", "deferrals", "run_id")).isEqualTo(5);
        assertThat(f.value(run, "run_inflight", "fence", "run_id")).isEqualTo(3);
        assertThat(f.cell(run)
                        .jdbc()
                        .sql(
                                "SELECT owner_node IS NULL AND lease_until IS NULL AND due_at=:due FROM scheduler.run_inflight WHERE run_id=:id")
                        .param("id", run)
                        .param("due", f.now().plusSeconds(5))
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void threeDeadRunsAutoPauseWithDurableIdempotentAuditRelay() {
        UUID job = f.job(12);
        for (int i = 0; i < 3; i++) {
            UUID run = f.run(job);
            f.settler().settle(List.of(f.result(run, 2, 11, 0)));
        }
        assertThat(status(job)).isEqualTo("AUTO_PAUSED");
        assertThat(f.value(job, "schedules", "status", "schedule_id")).isEqualTo(2);
        assertThat(f.value(job, "job_health", "consecutive_dead", "job_id")).isEqualTo(3);
        assertThat(f.cell(job)
                        .jdbc()
                        .sql("SELECT count(*) FROM scheduler.settlement_audits WHERE job_id=:id")
                        .param("id", job)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        var relay = new SettlementAuditRelay(f.cells, f.control);
        assertThat(relay.drainOnce()).isEqualTo(1);
        assertThat(relay.drainOnce()).isZero();
        assertThat(f.control
                        .sql(
                                "SELECT count(*) FROM scheduler.audit_log WHERE resource_id=:id AND actor='system:auto-pause' AND action='JOB_AUTO_PAUSED'")
                        .param("id", job.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void successResetsDeadStreakAndOptOutDisablesPause() {
        UUID job = f.job(12, "{\"schedule\":{\"type\":\"INTERVAL\"},\"autoPauseAfter\":0}");
        for (int i = 0; i < 4; i++) {
            UUID run = f.run(job);
            f.settler().settle(List.of(f.result(run, 2, 11, 0)));
        }
        assertThat(status(job)).isEqualTo("ACTIVE");
        UUID run = f.run(job);
        f.settler().settle(List.of(f.result(run, 1, 0, 200)));
        assertThat(f.value(job, "job_health", "consecutive_dead", "job_id")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"1,true,4", "4,false,4", "5,false,5"})
    void cancellationAndSkippingAreTerminalWithoutHealthOrDlq(int outcome, boolean cancel, int expected) {
        UUID job = f.job(12), run = f.run(job);
        f.cell(run)
                .jdbc()
                .sql("UPDATE scheduler.run_inflight SET cancel_requested=:cancel WHERE run_id=:id")
                .param("cancel", cancel)
                .param("id", run)
                .update();
        f.settler().settle(List.of(f.result(run, outcome, 0, 0)));
        assertThat(f.value(run, "run_history", "outcome", "run_id")).isEqualTo(expected);
        assertThat(f.count(run, "run_inflight")).isZero();
        assertThat(f.count(run, "dead_letters")).isZero();
        assertThat(f.cell(job)
                        .jdbc()
                        .sql("SELECT count(*) FROM scheduler.job_health WHERE job_id=:id")
                        .param("id", job)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @ParameterizedTest
    @CsvSource({"1,0,COMPLETED", "2,11,FAILED", "4,0,CANCELLED", "5,0,COMPLETED"})
    void oneTimeJobFinalizesOnlyCurrentOriginalOccurrence(int outcome, int error, String expected) {
        UUID job = f.job(12, "{\"schedule\":{\"type\":\"ONE_TIME\"}}"), run = f.run(job);
        f.settler().settle(List.of(f.result(run, outcome, error, 0)));
        assertThat(status(job)).isEqualTo(expected);
        UUID edited = f.job(12, "{\"schedule\":{\"type\":\"ONE_TIME\"}}"), old = f.run(edited);
        f.cell(edited)
                .jdbc()
                .sql("UPDATE scheduler.jobs SET version=2 WHERE job_id=:id")
                .param("id", edited)
                .update();
        f.settler().settle(List.of(f.result(old, 1, 0, 200)));
        assertThat(status(edited)).isEqualTo("ACTIVE");
    }

    @Test
    void workflowCallbackJoinsCellTransactionAndFailureRollsBackWholeGroup() {
        UUID job = f.job(12), run = f.run(job), task = task(12);
        var ordinary = f.result(run, 1, 0, 200);
        var result = f.result(task, 1, 0, 200);
        var failing = f.settler((r, resultMessage, outcome, at) -> {
            assertThat(TransactionSynchronizationManager.hasResource(
                            f.cell(task).database().dataSource()))
                    .isTrue();
            assertThat(f.count(task, "run_history")).isEqualTo(1);
            assertThat(f.count(run, "run_inflight")).isZero();
            throw new IllegalStateException("workflow transaction failure");
        });
        assertThatThrownBy(() -> failing.settle(List.of(ordinary, result))).isInstanceOf(IllegalStateException.class);
        assertThat(f.count(run, "run_history")).isZero();
        assertThat(f.count(task, "run_history")).isZero();
        assertThat(f.count(run, "run_inflight")).isEqualTo(1);
        assertThat(f.observed).isEmpty();
        assertThatThrownBy(() -> f.settler().settle(List.of(result))).isInstanceOf(IllegalStateException.class);
        f.settler((r, m, o, a) -> assertThat(o).isEqualTo(Outcome.SUCCEEDED)).settle(List.of(ordinary, result));
        assertThat(f.observed).hasSize(2);
    }

    @Test
    void partialCellCommitReplaysWithoutRepeatingCommittedCell() {
        UUID job = f.job(12), run = f.run(job), task = task(140);
        var first = f.result(run, 1, 0, 200);
        var second = f.result(task, 1, 0, 200);
        assertThatThrownBy(() -> f.settler().settle(List.of(second, first))).isInstanceOf(IllegalStateException.class);
        assertThat(f.count(run, "run_history")).isEqualTo(1);
        assertThat(f.count(task, "run_history")).isZero();
        assertThat(f.observed).hasSize(1);
        f.settler((r, m, o, a) -> {}).settle(List.of(first, second));
        assertThat(f.observed).hasSize(2);
        assertThat(f.count(run, "run_history")).isEqualTo(1);
        assertThat(f.count(task, "run_history")).isEqualTo(1);
    }

    @Test
    void deadLetterAfterRedriveReopensSameEntryWithNewFence() {
        UUID job = f.job(12), run = f.run(job);
        f.settler().settle(List.of(f.result(run, 2, 11, 0)));
        UUID dlq = f.cell(run)
                .jdbc()
                .sql("SELECT dlq_id FROM scheduler.dead_letters WHERE run_id=:id")
                .param("id", run)
                .query(UUID.class)
                .single();
        f.cell(run)
                .jdbc()
                .sql(
                        "UPDATE scheduler.dead_letters SET status=3,resolved_at=:now,resolved_by='user:test',redrive_mode=1 WHERE run_id=:id")
                .param("id", run)
                .param("now", f.now())
                .update();
        UUID temporary = f.run(job);
        f.cell(run)
                .jdbc()
                .sql("UPDATE scheduler.run_inflight SET run_id=:run,attempt=2,series_start=2,fence=8 WHERE run_id=:tmp")
                .param("run", run)
                .param("tmp", temporary)
                .update();
        f.settler().settle(List.of(f.result(run, 2, 11, 0)));
        assertThat(f.value(run, "dead_letters", "status", "run_id")).isEqualTo(1);
        assertThat(f.value(run, "dead_letters", "fence", "run_id")).isEqualTo(8);
        assertThat(f.cell(run)
                        .jdbc()
                        .sql(
                                "SELECT dlq_id=:dlq AND resolved_at IS NULL AND resolved_by IS NULL AND redrive_mode IS NULL FROM scheduler.dead_letters WHERE run_id=:id")
                        .param("id", run)
                        .param("dlq", dlq)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void concurrentBatchReplaySettlesEachRunOnce() throws Exception {
        UUID job = f.job(12);
        var results = new java.util.ArrayList<dev.kairos.proto.v1.Result>();
        for (int i = 0; i < 128; i++) results.add(f.result(f.run(job), 1, 0, 200));
        var settler = f.settler();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> settler.settle(results));
            var second = executor.submit(() -> settler.settle(results));
            first.get(30, java.util.concurrent.TimeUnit.SECONDS);
            second.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(f.observed).hasSize(128);
        assertThat(f.cell(job)
                        .jdbc()
                        .sql("SELECT count(*) FROM scheduler.run_history WHERE definition_id=:id")
                        .param("id", job)
                        .query(Long.class)
                        .single())
                .isEqualTo(128);
        assertThat(f.value(job, "job_health", "recent_count", "job_id")).isEqualTo(10);
        assertThatThrownBy(() -> settler.settle(java.util.Collections.nCopies(1001, results.getFirst())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void endpointPolicyOverridesTeamAndTeamOverridesPlatform() {
        String policy =
                "{\"maxRetries\":0,\"strategy\":\"EXPONENTIAL_JITTER\",\"baseDelaySec\":30,\"maxDelaySec\":600,\"maxAgeSec\":86400}";
        f.control
                .sql("UPDATE scheduler.teams SET retry_default=CAST(:policy AS jsonb) WHERE team_id=:id")
                .param("id", f.team)
                .param("policy", policy)
                .update();
        UUID run = f.run(f.job(12));
        f.settler().settle(List.of(f.result(run, 2, 7, 503)));
        assertThat(f.count(run, "dead_letters")).isEqualTo(1);
        f.endpointSpec("{\"retryPolicy\":" + policy.replace("\"maxRetries\":0", "\"maxRetries\":2") + "}");
        UUID retry = f.run(f.job(12));
        f.settler().settle(List.of(f.result(retry, 2, 7, 503)));
        assertThat(f.value(retry, "run_inflight", "attempt", "run_id")).isEqualTo(2);
    }

    @Test
    void auditReplayAfterControlCommitDoesNotDuplicateAudit() {
        UUID job = f.job(12);
        UUID audit = f.ids.newId();
        f.cell(job)
                .jdbc()
                .sql(
                        "INSERT INTO scheduler.settlement_audits(audit_id,at,team_id,job_id,consecutive_dead) VALUES (:id,:at,:team,:job,3)")
                .param("id", audit)
                .param("at", f.now())
                .param("team", f.team)
                .param("job", job)
                .update();
        f.control
                .sql(
                        "INSERT INTO scheduler.audit_log(audit_id,at,team_id,actor,action,resource_type,resource_id) VALUES (:id,:at,:team,'system:auto-pause','JOB_AUTO_PAUSED','job',:job)")
                .param("id", audit)
                .param("at", f.now())
                .param("team", f.team)
                .param("job", job.toString())
                .update();
        var relay = new SettlementAuditRelay(f.cells, f.control);
        relay.drainOnce();
        relay.scheduled();
        assertThat(f.control
                        .sql("SELECT count(*) FROM scheduler.audit_log WHERE audit_id=:id")
                        .param("id", audit)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(f.cell(job)
                        .jdbc()
                        .sql("SELECT count(*) FROM scheduler.settlement_audits WHERE audit_id=:id")
                        .param("id", audit)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private UUID task(int shard) {
        UUID job = f.job(shard),
                run = f.run(job),
                workflowRun = f.ids.newWorkflowRunId(KairosId.shardOf(job)).value();
        f.cell(run)
                .jdbc()
                .sql(
                        "INSERT INTO scheduler.workflow_runs(workflow_run_id,workflow_id,workflow_version,team_id,shard_id,scheduled_at,trigger,total) VALUES (:id,:workflow,1,:team,:shard,:at,1,1)")
                .param("id", workflowRun)
                .param("workflow", job)
                .param("team", f.team)
                .param("shard", shard)
                .param("at", f.now())
                .update();
        f.cell(run)
                .jdbc()
                .sql(
                        "UPDATE scheduler.run_inflight SET target_type=2,target_id=:target,task_key='task' WHERE run_id=:id")
                .param("target", workflowRun)
                .param("id", run)
                .update();
        return run;
    }

    private String status(UUID job) {
        return f.cell(job)
                .jdbc()
                .sql("SELECT status FROM scheduler.jobs WHERE job_id=:id")
                .param("id", job)
                .query(String.class)
                .single();
    }
}
