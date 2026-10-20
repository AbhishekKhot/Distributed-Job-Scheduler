package dev.kairos.engine.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.model.OutboxTopic;
import dev.kairos.domain.model.RunState;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.run.Fence;
import dev.kairos.engine.EngineFixtures;
import dev.kairos.engine.outbox.CellTransactionGuard;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.tx.CellTransactions;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Run creation on the real partitioned {@code run_inflight} and {@code outbox} tables: runs and dispatch rows commit or
 * roll back together, an occurrence is created once, payloads carry no secret values and immediate runs wake the
 * cell's watchers.
 */
@KairosIntegrationTest(kafka = false, redis = false)
class RunCreatorIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private static final String TRACE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private static final String SELECT_RUN = """
            SELECT run_id, team_id, target_type, definition_id, definition_version, target_id, task_key, endpoint_id,
                endpoint_version, scheduled_at, due_at, state, attempt, series_start, series_started_at, infra_attempt,
                deferrals, fence, owner_node, lease_until, remaining_deps, cancel_requested, priority, trigger,
                max_lateness_ms
            FROM scheduler.run_inflight WHERE shard_id = :shardId AND run_id = :runId
            """;
    private static final String COUNT_TARGET_RUNS =
            "SELECT count(*) FROM scheduler.run_inflight WHERE shard_id = :shardId AND target_id = :targetId";
    private static final String SELECT_OUTBOX = """
            SELECT topic, msg_key, payload, trace_parent, available_at
            FROM scheduler.outbox WHERE shard_id = :shardId AND run_id = :runId
            """;

    private final MutableClock clock = MutableClock.at(NOW);
    private DataSourceRegistry registry;
    private CellTransactions cells;
    private RunFactory factory;
    private RunCreator creator;
    private KairosPostgres postgres;

    @BeforeEach
    void setUp(KairosPostgres container) {
        postgres = container;
        registry = EngineFixtures.registry(container);
        CellRouter router = EngineFixtures.router(registry);
        cells = new CellTransactions(router);
        CellTransactionGuard guard = new CellTransactionGuard(router);
        factory = new RunFactory(TestIds.factory(clock), clock);
        creator = new RunCreator(
                new RunInserter(guard),
                new OutboxWriter(guard, TestIds.factory(clock), clock),
                new FireNotifier(guard),
                clock);
    }

    @Test
    void queuedRunAndItsDispatchRowCommitTogether() {
        RunTarget job = EngineFixtures.job(150, 9);
        PlannedRun planned = factory.scheduled(job, NOW.plusSeconds(2), Duration.ofMillis(250));

        RunCreation creation = cells.execute(job.shard(), jdbc -> creator.create(planned, TRACE));

        assertThat(creation.created()).containsExactly(planned.run());
        assertThat(creation.duplicates()).isEmpty();
        Map<String, Object> run = runRow(planned.run());
        assertThat(run.get("team_id")).isEqualTo(job.teamId().value());
        assertThat(run.get("target_type")).isEqualTo(1);
        assertThat(run.get("definition_id")).isEqualTo(job.definitionId());
        assertThat(run.get("definition_version")).isEqualTo(3);
        assertThat(run.get("target_id")).isEqualTo(job.targetId());
        assertThat(run.get("task_key")).isEqualTo("");
        assertThat(run.get("endpoint_id")).isEqualTo(job.endpointId().value());
        assertThat(run.get("endpoint_version")).isEqualTo(2);
        assertThat(instant(run.get("scheduled_at"))).isEqualTo(NOW.plusSeconds(2));
        assertThat(instant(run.get("due_at"))).isEqualTo(NOW.plusMillis(2_250));
        assertThat(run.get("state")).isEqualTo(RunState.QUEUED.code());
        assertThat(run.get("attempt")).isEqualTo(1);
        assertThat(run.get("series_start")).isEqualTo(1);
        assertThat(instant(run.get("series_started_at"))).isEqualTo(NOW);
        assertThat(run.get("infra_attempt")).isEqualTo(0);
        assertThat(run.get("deferrals")).isEqualTo(0);
        assertThat(run.get("fence")).isEqualTo(1);
        assertThat(run.get("owner_node")).isNull();
        assertThat(run.get("lease_until")).isNull();
        assertThat(run.get("remaining_deps")).isEqualTo(0);
        assertThat(run.get("cancel_requested")).isEqualTo(false);
        assertThat(run.get("priority")).isEqualTo(9);
        assertThat(run.get("trigger")).isEqualTo(Trigger.SCHEDULE.code());
        assertThat(run.get("max_lateness_ms")).isNull();

        List<Map<String, Object>> outbox = outboxRows(planned.run());
        assertThat(outbox).hasSize(1);
        Map<String, Object> row = outbox.getFirst();
        assertThat(row.get("topic")).isEqualTo(OutboxTopic.RUN_HIGH.code());
        assertThat(row.get("msg_key"))
                .isEqualTo(ProtoIds.toBytes(job.targetId()).toByteArray());
        assertThat(row.get("trace_parent")).isEqualTo(TRACE);
        assertThat(instant(row.get("available_at"))).isEqualTo(NOW.plusMillis(2_250));
    }

    @Test
    void storedDispatchPayloadDescribesTheRunWithoutSecretValues() throws Exception {
        RunTarget job = EngineFixtures.job(40, 6);
        PlannedRun planned = factory.immediate(job, NOW);

        cells.execute(job.shard(), jdbc -> creator.create(planned, null));

        byte[] payload = (byte[]) outboxRows(planned.run()).getFirst().get("payload");
        Dispatch dispatch = Dispatch.parseFrom(payload);
        assertThat(ProtoIds.toUuid(dispatch.getRunId()))
                .isEqualTo(planned.run().runId().value());
        assertThat(dispatch.getFence()).isEqualTo(1);
        assertThat(dispatch.getRequest().getSecretRefsList()).containsExactly(EngineFixtures.SECRET_REF);
        assertThat(new String(payload, StandardCharsets.ISO_8859_1)).doesNotContain(EngineFixtures.SECRET_VALUE);
    }

    @Test
    void runsAndDispatchRowsRollBackTogether() {
        RunTarget job = EngineFixtures.job(20, 5);
        PlannedRun planned = factory.immediate(job, NOW);

        assertThatThrownBy(() -> cells.execute(job.shard(), jdbc -> {
                    creator.create(planned, null);
                    throw new IllegalStateException("advancing the schedule failed");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countRuns(job)).isZero();
        assertThat(outboxRows(planned.run())).isEmpty();
    }

    @Test
    void anOccurrenceIsCreatedOnce() {
        RunTarget job = EngineFixtures.job(220, 3);
        PlannedRun first = factory.scheduled(job, NOW, Duration.ZERO);
        PlannedRun again = factory.scheduled(job, NOW, Duration.ZERO);

        RunCreation created = cells.execute(job.shard(), jdbc -> creator.create(first, null));
        RunCreation repeated = cells.execute(job.shard(), jdbc -> creator.create(again, null));

        assertThat(created.created()).containsExactly(first.run());
        assertThat(repeated.created()).isEmpty();
        assertThat(repeated.duplicates()).containsExactly(again.run());
        assertThat(countRuns(job)).isEqualTo(1);
        assertThat(outboxRows(first.run())).hasSize(1);
        assertThat(outboxRows(again.run())).isEmpty();
    }

    @Test
    void duplicatesWithinOneCallAreCreatedOnce() {
        RunTarget job = EngineFixtures.job(221, 3);
        PlannedRun first = factory.manual(job, NOW);
        PlannedRun twin = factory.manual(job, NOW);

        RunCreation creation = cells.execute(job.shard(), jdbc -> creator.create(List.of(first, twin), null));

        assertThat(creation.created()).containsExactly(first.run());
        assertThat(creation.duplicates()).containsExactly(twin.run());
        assertThat(countRuns(job)).isEqualTo(1);
    }

    @Test
    void delayedAndWaitingRunsGetNoDispatchRow() {
        RunTarget job = EngineFixtures.job(30, 5);
        RunTarget task = EngineFixtures.task(30, "settle");
        PlannedRun delayed = factory.scheduled(job, NOW.plusSeconds(60), Duration.ZERO);
        PlannedRun waiting = factory.waiting(task, NOW, Trigger.MANUAL, 2);

        RunCreation creation = cells.execute(job.shard(), jdbc -> creator.create(List.of(delayed, waiting), null));

        assertThat(creation.created()).containsExactly(delayed.run(), waiting.run());
        assertThat(outboxRows(delayed.run())).isEmpty();
        assertThat(outboxRows(waiting.run())).isEmpty();
        Map<String, Object> waitingRow = runRow(waiting.run());
        assertThat(waitingRow.get("task_key")).isEqualTo("settle");
        assertThat(waitingRow.get("remaining_deps")).isEqualTo(2);
        assertThat(waitingRow.get("state")).isEqualTo(RunState.WAITING.code());
    }

    @Test
    void largeBackfillIsInsertedInChunks() {
        RunTarget job = EngineFixtures.job(31, 2);
        List<Instant> occurrences = new ArrayList<>();
        for (int i = 1; i <= RunInserter.CHUNK_SIZE + 1; i++) {
            occurrences.add(NOW.minus(Duration.ofMinutes(i)));
        }
        List<PlannedRun> runs = factory.backfill(job, occurrences, 100);

        RunCreation creation = cells.execute(job.shard(), jdbc -> creator.create(runs, null));

        assertThat(creation.created()).hasSize(RunInserter.CHUNK_SIZE + 1);
        assertThat(countRuns(job)).isEqualTo(RunInserter.CHUNK_SIZE + 1);
        assertThat(outboxRows(runs.getLast().run())).isEmpty();
    }

    @Test
    void redriveRecreatesTheRunUnderItsOriginalId() {
        RunTarget job = EngineFixtures.job(32, 5);
        RunId original = TestIds.runId(32);
        PlannedRun redrive = factory.redrive(job, original, NOW.minusSeconds(600), 6, new Fence(11));

        cells.execute(job.shard(), jdbc -> creator.create(redrive, null));

        Map<String, Object> row = runRow(redrive.run());
        assertThat(row.get("run_id")).isEqualTo(original.value());
        assertThat(row.get("attempt")).isEqualTo(7);
        assertThat(row.get("series_start")).isEqualTo(7);
        assertThat(row.get("fence")).isEqualTo(11);
        assertThat(row.get("trigger")).isEqualTo(Trigger.REDRIVE.code());
    }

    @Test
    void refusesToCreateRunsOutsideATransactionOnTheirCell() {
        RunTarget job = EngineFixtures.job(33, 5);
        PlannedRun planned = factory.immediate(job, NOW);
        RunId otherCell = TestIds.runId(160);

        assertThatThrownBy(() -> creator.create(planned, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CellTransactions");
        assertThatThrownBy(() -> cells.execute(otherCell.shard(), jdbc -> creator.create(planned, null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countRuns(job)).isZero();
    }

    @Test
    void createsNothingForAnEmptyListAndRefusesRunningRuns() {
        PlannedRun queued = factory.immediate(EngineFixtures.job(34, 5), NOW);
        InflightRun running = DispatchMessageBuilder.redispatch(queued.run(), queued.call(), NOW, null)
                .run();
        InflightRun claimed = new InflightRun(
                running.runId(),
                running.teamId(),
                running.targetType(),
                running.definitionId(),
                running.definitionVersion(),
                running.targetId(),
                running.taskKey(),
                running.endpointId(),
                running.endpointVersion(),
                running.scheduledAt(),
                running.dueAt(),
                RunState.RUNNING,
                1,
                1,
                NOW,
                0,
                running.fence().next(),
                0,
                running.priority(),
                running.trigger(),
                null);

        assertThat(creator.create(List.of(), null).created()).isEmpty();
        assertThatThrownBy(() -> creator.create(new PlannedRun(claimed, queued.call()), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runsQueuedByTheApiWakeTheCellsWatchers() throws SQLException {
        RunTarget scheduled = EngineFixtures.job(60, 5);
        RunTarget immediate = EngineFixtures.job(61, 5);
        RunTarget manual = EngineFixtures.job(62, 5);
        DataSource cell0 = postgres.cellDataSource(0);

        try (Connection listener = cell0.getConnection()) {
            listener.setAutoCommit(true);
            try (Statement statement = listener.createStatement()) {
                statement.execute("LISTEN " + FireNotifier.channel(0));
            }
            try {
                // The fire poller queues scheduled runs itself and needs no wake-up; the API's runs do.
                cells.execute(
                        scheduled.shard(),
                        jdbc -> creator.create(factory.scheduled(scheduled, NOW, Duration.ZERO), null));
                cells.execute(
                        immediate.shard(),
                        jdbc -> creator.create(
                                List.of(factory.immediate(immediate, NOW), factory.manual(manual, NOW)), null));

                PGNotification[] notifications =
                        listener.unwrap(PGConnection.class).getNotifications(10_000);

                assertThat(notifications).isNotNull();
                assertThat(Arrays.stream(notifications)
                                .map(PGNotification::getName)
                                .collect(Collectors.toSet()))
                        .containsExactly("kairos_fire_0");
                assertThat(Arrays.stream(notifications).map(PGNotification::getParameter))
                        .containsExactlyInAnyOrder("61", "62");
            } finally {
                try (Statement statement = listener.createStatement()) {
                    statement.execute("UNLISTEN *");
                }
            }
        }
    }

    @Test
    void channelNamesFollowTheCellNumber() {
        assertThat(FireNotifier.channel(1)).isEqualTo("kairos_fire_1");
        assertThatThrownBy(() -> FireNotifier.channel(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void occurrenceInsertUsesTheUniqueIndexAsArbiter() {
        InflightRun run = factory.immediate(EngineFixtures.job(70, 5), NOW).run();
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        bindForExplain(parameters, run);

        String plan = cell0()
                .jdbc()
                .sql("EXPLAIN (COSTS OFF) " + RunInserter.insertSql(1))
                .paramSource(parameters)
                .query(String.class)
                .list()
                .stream()
                .collect(Collectors.joining("\n"));

        assertThat(plan).contains("Conflict Resolution: NOTHING").contains("Conflict Arbiter Indexes");
        assertThat(plan).doesNotContain("Seq Scan");
    }

    @Test
    void insertStatementBoundsItsRowCount() {
        assertThatThrownBy(() -> RunInserter.insertSql(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunInserter.insertSql(RunInserter.CHUNK_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void bindForExplain(MapSqlParameterSource parameters, InflightRun run) {
        parameters
                .addValue("shardId0", (short) run.shard().value())
                .addValue("runId0", run.runId().value())
                .addValue("teamId0", run.teamId().value())
                .addValue("targetType0", (short) run.targetType().code())
                .addValue("definitionId0", run.definitionId())
                .addValue("definitionVersion0", run.definitionVersion())
                .addValue("targetId0", run.targetId())
                .addValue("taskKey0", run.taskKeyColumn())
                .addValue("endpointId0", run.endpointId().value())
                .addValue("endpointVersion0", run.endpointVersion())
                .addValue("scheduledAt0", OffsetDateTime.ofInstant(run.scheduledAt(), ZoneOffset.UTC))
                .addValue("dueAt0", OffsetDateTime.ofInstant(run.dueAt(), ZoneOffset.UTC))
                .addValue("state0", (short) run.state().code())
                .addValue("attempt0", (short) run.attempt())
                .addValue("seriesStart0", (short) run.seriesStart())
                .addValue("seriesStartedAt0", OffsetDateTime.ofInstant(run.seriesStartedAt(), ZoneOffset.UTC))
                .addValue("infraAttempt0", (short) run.infraAttempt())
                .addValue("fence0", run.fence().value())
                .addValue("remainingDeps0", (short) run.remainingDeps())
                .addValue("priority0", (short) run.priority().value())
                .addValue("trigger0", (short) run.trigger().code())
                .addValue("maxLatenessMs0", null, Types.INTEGER);
    }

    private Database cellOf(InflightRun run) {
        return run.shard().value() < 128 ? cell0() : cell1();
    }

    private Database cell0() {
        return registry.cell(Database.cellName(0)).orElseThrow();
    }

    private Database cell1() {
        return registry.cell(Database.cellName(1)).orElseThrow();
    }

    private Map<String, Object> runRow(InflightRun run) {
        return cellOf(run)
                .jdbc()
                .sql(SELECT_RUN)
                .param("shardId", (short) run.shard().value())
                .param("runId", run.runId().value())
                .query()
                .singleRow();
    }

    private List<Map<String, Object>> outboxRows(InflightRun run) {
        return cellOf(run)
                .jdbc()
                .sql(SELECT_OUTBOX)
                .param("shardId", (short) run.shard().value())
                .param("runId", run.runId().value())
                .query()
                .listOfRows();
    }

    private long countRuns(RunTarget target) {
        Database cell = target.shard().value() < 128 ? cell0() : cell1();
        return cell.jdbc()
                .sql(COUNT_TARGET_RUNS)
                .param("shardId", (short) target.shard().value())
                .param("targetId", target.targetId())
                .query(Long.class)
                .single();
    }

    private static Instant instant(Object value) {
        // The driver returns timestamptz as java.sql.Timestamp from getObject without a type.
        return value instanceof OffsetDateTime time ? time.toInstant() : ((Timestamp) value).toInstant();
    }
}
