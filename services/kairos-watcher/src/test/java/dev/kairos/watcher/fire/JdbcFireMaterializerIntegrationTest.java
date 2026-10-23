package dev.kairos.watcher.fire;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.model.DstPolicy;
import dev.kairos.domain.model.MisfirePolicy;
import dev.kairos.domain.model.ScheduleKind;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.schedule.Fire;
import dev.kairos.domain.shard.Shard;
import dev.kairos.engine.outbox.CellTransactionGuard;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.engine.run.FireNotifier;
import dev.kairos.engine.run.RunCreator;
import dev.kairos.engine.run.RunFactory;
import dev.kairos.engine.run.RunInserter;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRange;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.cell.CellStatus;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.RepositoryFiles;
import dev.kairos.watcher.lease.OwnedShards;
import dev.kairos.watcher.lease.ShardLeaseManager;
import dev.kairos.watcher.node.NodeIdentity;
import dev.kairos.watcher.node.NodeRegistry;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Real job and workflow materialization on the versioned control and partitioned cell schemas. */
@KairosIntegrationTest(kafka = false, redis = false)
class JdbcFireMaterializerIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final Shard SHARD = Shard.of(128);
    private static Database control;
    private static Cell cell;
    private static CellRouter router;
    private static KairosIdFactory ids;
    private static JdbcFireMaterializer materializer;

    @BeforeAll
    static void schema(KairosPostgres postgres) {
        apply(
                postgres.controlOwnerDataSource(),
                "control/V2026_10_20_0900__create_control_schema.sql",
                "scheduler_nodes",
                postgres);
        apply(
                postgres.cellOwnerDataSource(1),
                "cell/V2026_10_20_0910__create_cell_schema.sql",
                "shard_leases",
                postgres);
        apply(postgres.cellOwnerDataSource(1), "cell/V2026_10_20_0915__partition_cell_tables.sql", null, postgres);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        control = Database.of("control", postgres.controlOwnerDataSource(), DatabaseOptions.defaults(), meters);
        Database first = Database.of("cell-0", postgres.cellOwnerDataSource(0), DatabaseOptions.defaults(), meters);
        Database second = Database.of("cell-1", postgres.cellOwnerDataSource(1), DatabaseOptions.defaults(), meters);
        cell = new Cell(new CellRange(1, 128, 255, "cell-1", CellStatus.ACTIVE), second);
        router = new CellRouter(
                () -> List.of(new CellRange(0, 0, 127, "cell-0", CellStatus.ACTIVE), cell.range()),
                Map.of("cell-0", first, "cell-1", second),
                Duration.ofMinutes(1),
                MutableClock.at(NOW));
        MutableClock clock = MutableClock.at(NOW);
        ids = new KairosIdFactory(clock, new SecureRandom());
        CellTransactionGuard guard = new CellTransactionGuard(router);
        RunCreator creator = new RunCreator(
                new RunInserter(guard), new OutboxWriter(guard, ids, clock), new FireNotifier(guard), clock);
        materializer = new JdbcFireMaterializer(
                control.jdbc(), JsonMapper.builder().build(), ids, new RunFactory(ids, clock), creator);
    }

    @Test
    void jobOccurrenceCreatesOnePinnedRunAndDispatchDespiteRepeat() throws Exception {
        UUID team = team();
        UUID endpoint = endpoint(team, "job-target");
        UUID job = ids.newJobId(SHARD).value();
        String spec =
                "{\"endpoint\":\"job-target\",\"request\":{\"pathParams\":{\"id\":\"42\"},\"headers\":{\"X-Mode\":\"override\"},\"body\":null}}";
        cell.jdbc()
                .sql("""
                INSERT INTO scheduler.jobs (job_id, team_id, name, version, endpoint_id, spec, created_by, created_at, updated_at)
                VALUES (:id, :team, :name, 1, :endpoint, CAST(:spec AS jsonb), :actor, :now, :now)
                """)
                .param("id", job)
                .param("team", team)
                .param("name", "job-" + job)
                .param("endpoint", endpoint)
                .param("spec", spec)
                .param("actor", "user:" + ids.newId())
                .param("now", Timestamp.from(NOW))
                .update();
        cell.jdbc()
                .sql("""
                INSERT INTO scheduler.job_versions (job_id, version, spec, created_at)
                VALUES (:id, 1, CAST(:spec AS jsonb), :now)
                """)
                .param("id", job)
                .param("spec", spec)
                .param("now", Timestamp.from(NOW))
                .update();
        FireSchedule schedule = schedule(job, team, TargetType.JOB);
        Fire fire = new Fire(NOW, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        int first = cell.transactions().execute(jdbc -> materializer.create(cell, schedule, List.of(fire)));
        int duplicate = cell.transactions().execute(jdbc -> materializer.create(cell, schedule, List.of(fire)));
        assertThat(first).isEqualTo(1);
        assertThat(duplicate).isZero();
        assertThat(count("SELECT count(*) FROM scheduler.run_inflight WHERE target_id = :id", job))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM scheduler.outbox WHERE run_id IN (SELECT run_id FROM scheduler.run_inflight WHERE target_id = :id)",
                        job))
                .isEqualTo(1);
        int pinnedVersion = cell.jdbc()
                .sql("SELECT endpoint_version FROM scheduler.run_inflight WHERE target_id = :id")
                .param("id", job)
                .query(Integer.class)
                .single();
        assertThat(pinnedVersion).isEqualTo(1);
        byte[] payload =
                cell.jdbc().sql("""
                SELECT o.payload FROM scheduler.outbox o
                JOIN scheduler.run_inflight r ON r.run_id = o.run_id
                WHERE r.target_id = :id
                """).param("id", job).query(byte[].class).single();
        Dispatch dispatch = Dispatch.parseFrom(payload);
        assertThat(dispatch.getRequest().getPathParamsMap()).containsEntry("id", "42");
        assertThat(dispatch.getRequest().getBody().toStringUtf8()).isEqualTo("null");
        assertThat(dispatch.getRequest().getHeadersList()).singleElement().satisfies(header -> {
            assertThat(header.getName()).isEqualTo("X-Mode");
            assertThat(header.getValue()).isEqualTo("override");
        });
    }

    @Test
    void workflowCreatesRootDispatchesAndWaitingChildOnce() throws Exception {
        UUID team = team();
        endpoint(team, "workflow-target");
        UUID workflow = ids.newWorkflowId(SHARD).value();
        String spec = """
                {"tasks":{"first":{"endpoint":"workflow-target","request":{"headers":{"Authorization":"Bearer {{ run.id }}"}}},
                          "second":{"endpoint":"workflow-target"},
                          "child":{"endpoint":"workflow-target","dependsOn":["first","second"]}}}
                """;
        cell.jdbc()
                .sql("""
                INSERT INTO scheduler.workflows (workflow_id, team_id, name, version, spec, created_by, created_at, updated_at)
                VALUES (:id, :team, :name, 1, CAST(:spec AS jsonb), :actor, :now, :now)
                """)
                .param("id", workflow)
                .param("team", team)
                .param("name", "workflow-" + workflow)
                .param("spec", spec)
                .param("actor", "user:" + ids.newId())
                .param("now", Timestamp.from(NOW))
                .update();
        cell.jdbc()
                .sql("""
                INSERT INTO scheduler.workflow_versions (workflow_id, version, spec, created_at)
                VALUES (:id, 1, CAST(:spec AS jsonb), :now)
                """)
                .param("id", workflow)
                .param("spec", spec)
                .param("now", Timestamp.from(NOW))
                .update();
        FireSchedule schedule = schedule(workflow, team, TargetType.WORKFLOW);
        Fire fire = new Fire(NOW, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        int first = cell.transactions().execute(jdbc -> materializer.create(cell, schedule, List.of(fire)));
        int duplicate = cell.transactions().execute(jdbc -> materializer.create(cell, schedule, List.of(fire)));
        assertThat(first).isEqualTo(1);
        assertThat(duplicate).isZero();
        assertThat(count("SELECT count(*) FROM scheduler.workflow_runs WHERE workflow_id = :id", workflow))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM scheduler.run_inflight WHERE definition_id = :id", workflow))
                .isEqualTo(3);
        assertThat(count(
                        "SELECT count(*) FROM scheduler.run_inflight WHERE definition_id = :id AND state = 2",
                        workflow))
                .isEqualTo(2);
        assertThat(count(
                        "SELECT count(*) FROM scheduler.run_inflight WHERE definition_id = :id AND state = 1 AND remaining_deps = 2",
                        workflow))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM scheduler.outbox WHERE run_id IN (SELECT run_id FROM scheduler.run_inflight WHERE definition_id = :id)",
                        workflow))
                .isEqualTo(2);
        byte[] payload =
                cell.jdbc().sql("""
                SELECT o.payload FROM scheduler.outbox o
                JOIN scheduler.run_inflight r ON r.run_id = o.run_id
                WHERE r.definition_id = :id AND r.task_key = 'first'
                """).param("id", workflow).query(byte[].class).single();
        Dispatch dispatch = Dispatch.parseFrom(payload);
        assertThat(dispatch.getRequest().getHeadersList())
                .noneMatch(header -> header.getName().equalsIgnoreCase("Authorization"));
    }

    @Test
    void committedNotificationWakesAnOwnedShardWithoutPeriodicPolling() throws Exception {
        UUID team = team();
        UUID endpoint = endpoint(team, "notify-target");
        UUID job = ids.newJobId(Shard.of(0)).value();
        String spec = "{\"endpoint\":\"notify-target\"}";
        cell.jdbc()
                .sql("""
                INSERT INTO scheduler.jobs (job_id, team_id, name, version, endpoint_id, spec, created_by, created_at, updated_at)
                VALUES (:id, :team, :name, 1, :endpoint, CAST(:spec AS jsonb), :actor, :now, :now)
                """)
                .param("id", job)
                .param("team", team)
                .param("name", "notify-" + job)
                .param("endpoint", endpoint)
                .param("spec", spec)
                .param("actor", "user:" + ids.newId())
                .param("now", Timestamp.from(NOW))
                .update();
        cell.jdbc()
                .sql("""
                INSERT INTO scheduler.schedules (
                    schedule_id, target_type, team_id, endpoint_id, shard_id, status, next_fire_at,
                    kind, timezone, spread_ms, dst_policy, misfire_policy, max_catchup,
                    definition_version, priority)
                VALUES (:id, 1, :team, :endpoint, 0, 1, :now, 1, 'UTC', 0, 1, 2, 100, 1, 5)
                """)
                .param("id", job)
                .param("team", team)
                .param("endpoint", endpoint)
                .param("now", Timestamp.from(NOW))
                .update();

        CellRouter oneCell = new CellRouter(
                () -> List.of(new CellRange(1, 0, 255, "cell-1", CellStatus.ACTIVE)),
                Map.of("cell-1", cell.database()),
                Duration.ofMinutes(1),
                Clock.systemUTC());
        NodeIdentity identity =
                NodeIdentity.watcher("fire-notify-test", Clock.systemUTC().instant(), "test");
        OwnedShards owned = new OwnedShards();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ShardLeaseManager manager = new ShardLeaseManager(
                oneCell,
                new NodeRegistry(control.jdbc(), identity, Duration.ofMinutes(1)),
                owned,
                Duration.ofSeconds(15),
                Duration.ofSeconds(3),
                2,
                meters);
        manager.reconcile();
        assertThat(owned.mayStart(
                        1,
                        0,
                        owned.inCell(1).getFirst().generation(),
                        Clock.systemUTC().instant()))
                .isTrue();
        AtomicInteger called = new AtomicInteger();
        ShardedPoller idle = new ShardedPoller(
                oneCell, new OwnedShards(), identity.nodeId(), MutableClock.at(NOW), Duration.ZERO, meters);
        FirePoller poller = new FirePoller(
                idle,
                new FireScheduleStore(),
                (currentCell, schedule, fires) -> {
                    if (schedule.id().equals(job)) called.incrementAndGet();
                    return fires.size();
                },
                MutableClock.at(NOW),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                meters);
        FireNotificationListener listener =
                new FireNotificationListener(oneCell, owned, identity, Clock.systemUTC(), poller);
        try {
            for (int retry = 0; retry < 30 && called.get() == 0; retry++) {
                cell.jdbc()
                        .sql("SELECT pg_notify(:channel, :payload)")
                        .param("channel", FireNotifier.channel(1))
                        .param("payload", "0")
                        .query((rs, row) -> 1)
                        .single();
                Thread.sleep(100);
            }
            assertThat(called).hasValue(1);
            assertThat(count("SELECT count(*) FROM scheduler.schedules WHERE schedule_id = :id", job))
                    .isZero();
        } finally {
            listener.close();
            poller.close();
            idle.close();
            manager.close();
        }
    }

    private static FireSchedule schedule(UUID id, UUID team, TargetType type) {
        return new FireSchedule(
                id,
                team,
                type,
                128,
                ScheduleKind.ONE_TIME,
                NOW,
                null,
                "UTC",
                null,
                0,
                DstPolicy.DEFAULT,
                MisfirePolicy.RUN_LATEST,
                100,
                1,
                5,
                null);
    }

    private static UUID team() {
        UUID team = ids.newTeamId().value();
        control.jdbc()
                .sql("""
                INSERT INTO scheduler.teams (team_id, slug, created_at, updated_at)
                VALUES (:id, :slug, :now, :now)
                """)
                .param("id", team)
                .param("slug", "fire-" + team.toString().substring(28))
                .param("now", Timestamp.from(NOW))
                .update();
        return team;
    }

    private static UUID endpoint(UUID team, String name) {
        UUID id = ids.newEndpointId().value();
        String spec = "{\"timeoutMs\":1500,\"auth\":{\"type\":\"NONE\"},\"defaultHeaders\":{\"X-Mode\":\"default\"}}";
        control.jdbc()
                .sql("""
                INSERT INTO scheduler.api_endpoints (endpoint_id, team_id, name, version, spec, created_at, updated_at)
                VALUES (:id, :team, :name, 1, CAST(:spec AS jsonb), :now, :now)
                """)
                .param("id", id)
                .param("team", team)
                .param("name", name)
                .param("spec", spec)
                .param("now", Timestamp.from(NOW))
                .update();
        return id;
    }

    private static long count(String sql, UUID id) {
        return cell.jdbc().sql(sql).param("id", id).query(Long.class).single();
    }

    private static void apply(DataSource owner, String script, String preexisting, KairosPostgres postgres) {
        String sql;
        try {
            sql = Files.readString(
                            RepositoryFiles.resolve("jobs/kairos-migrations/src/main/resources/db/" + script),
                            StandardCharsets.UTF_8)
                    .replace("${appRole}", postgres.appUsername());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        if (preexisting != null) {
            sql = sql.replace(
                    "CREATE TABLE scheduler." + preexisting, "CREATE TABLE IF NOT EXISTS scheduler." + preexisting);
        }
        try (Connection connection = owner.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not apply " + script, failure);
        }
    }
}
