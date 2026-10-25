package dev.kairos.watcher.delayed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.shard.Shard;
import dev.kairos.engine.lock.RedisConnector;
import dev.kairos.engine.lock.RedisProperties;
import dev.kairos.engine.outbox.CellTransactionGuard;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.engine.run.CallSpec;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRange;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.cell.CellStatus;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.RequestSnapshot;
import dev.kairos.testsupport.KairosRedis;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.watcher.lease.OwnedShards;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Delayed queue transitions, pacing, suspension and rollback against PostgreSQL. */
class DelayedPollerIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final Shard SHARD = Shard.of(0);
    private static final CallSpec CALL = new CallSpec(
            1_500,
            RequestSnapshot.newBuilder().putPathParams("source", "delayed").build());
    private static Database database;
    private static Cell cell;
    private static CellRouter router;
    private static KairosIdFactory ids;
    private static PostgreSQLContainer postgres;
    private static RedisConnector redis;

    @BeforeAll
    static void schema() {
        postgres = new PostgreSQLContainer(DockerImageName.parse(
                                "postgres:18.6@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336")
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("delayed")
                .withUsername("delayed")
                .withPassword("delayed");
        postgres.start();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        database = Database.of("cell-0", dataSource, DatabaseOptions.defaults(), meters);
        database.jdbc().sql("CREATE SCHEMA IF NOT EXISTS scheduler").update();
        database.jdbc().sql("""
                CREATE TABLE IF NOT EXISTS scheduler.run_inflight (
                  shard_id smallint NOT NULL, run_id uuid NOT NULL, team_id uuid NOT NULL,
                  target_type smallint NOT NULL, definition_id uuid NOT NULL,
                  definition_version integer NOT NULL, target_id uuid NOT NULL,
                  task_key text NOT NULL DEFAULT '', endpoint_id uuid NOT NULL,
                  endpoint_version integer NOT NULL, scheduled_at timestamptz NOT NULL,
                  due_at timestamptz NOT NULL, state smallint NOT NULL, attempt smallint NOT NULL,
                  series_start smallint NOT NULL, series_started_at timestamptz NOT NULL,
                  infra_attempt smallint NOT NULL, deferrals integer NOT NULL DEFAULT 0,
                  fence integer NOT NULL, owner_node text, lease_until timestamptz,
                  remaining_deps smallint NOT NULL DEFAULT 0, cancel_requested boolean NOT NULL DEFAULT false,
                  priority smallint NOT NULL, trigger smallint NOT NULL, max_lateness_ms integer,
                  PRIMARY KEY (shard_id, run_id))
                """).update();
        database.jdbc().sql("""
                CREATE TABLE IF NOT EXISTS scheduler.outbox (
                  shard_id smallint NOT NULL, outbox_id uuid NOT NULL, topic smallint NOT NULL,
                  msg_key bytea NOT NULL, payload bytea NOT NULL, trace_parent text, run_id uuid,
                  available_at timestamptz NOT NULL, created_at timestamptz NOT NULL,
                  PRIMARY KEY (shard_id, outbox_id))
                """).update();
        database.jdbc()
                .sql("CREATE TABLE scheduler.job_versions (job_id uuid, version integer, spec jsonb)")
                .update();
        database.jdbc()
                .sql("CREATE TABLE scheduler.teams (team_id uuid, quotas jsonb)")
                .update();
        database.jdbc()
                .sql("CREATE TABLE scheduler.api_endpoints (endpoint_id uuid, team_id uuid, status text)")
                .update();
        database.jdbc()
                .sql("CREATE TABLE scheduler.api_endpoint_versions (endpoint_id uuid, version integer, spec jsonb)")
                .update();
        cell = new Cell(new CellRange(0, 0, 255, "cell-0", CellStatus.ACTIVE), database);
        router = new CellRouter(
                () -> List.of(cell.range()), Map.of("cell-0", database), Duration.ofMinutes(1), MutableClock.at(NOW));
        ids = new KairosIdFactory(MutableClock.at(NOW), new SecureRandom());
        KairosRedis redisContainer = KairosRedis.get();
        redis = RedisConnector.create(
                RedisProperties.of(
                        redisContainer.host(),
                        redisContainer.port(),
                        redisContainer.username(),
                        redisContainer.password()),
                meters,
                MutableClock.at(NOW));
        redis.connect();
    }

    @AfterAll
    static void stopPostgres() {
        redis.close();
        postgres.close();
    }

    @Test
    void dueRetryQueuesWithNewFenceAndDispatchWhileFutureRunWaits() throws Exception {
        RunId due = seed(NOW, 1, 2);
        RunId future = seed(NOW.plusSeconds(1), 1, 2);
        try (Harness harness = harness("ACTIVE")) {
            poll(harness);
        }
        assertThat(state(due)).containsExactly(2, 2, 0);
        assertThat(state(future)).containsExactly(4, 1, 0);
        byte[] payload = database.jdbc()
                .sql("SELECT payload FROM scheduler.outbox WHERE run_id = :run")
                .param("run", due.value())
                .query(byte[].class)
                .single();
        Dispatch dispatch = Dispatch.parseFrom(payload);
        assertThat(dispatch.getFence()).isEqualTo(2);
        assertThat(dispatch.getRequest().getPathParamsMap()).containsEntry("source", "delayed");
    }

    @Test
    void suspendedEndpointStaysDelayedAndIncrementsDeferrals() {
        RunId run = seed(NOW, 4, 1);
        try (Harness harness = harness("SUSPENDED")) {
            poll(harness);
        }
        assertThat(state(run)).containsExactly(4, 4, 1);
        Instant due = database.jdbc()
                .sql("SELECT due_at FROM scheduler.run_inflight WHERE run_id = :run")
                .param("run", run.value())
                .query((rs, row) -> rs.getTimestamp(1).toInstant())
                .single();
        assertThat(due).isEqualTo(NOW.plusSeconds(15));
        assertThat(outboxCount(run)).isZero();
    }

    @Test
    void pinnedVersionsRebuildTheDispatchSnapshotAndCurrentEndpointState() {
        UUID team = ids.newTeamId().value();
        UUID target = ids.newJobId(SHARD).value();
        UUID endpoint = ids.newEndpointId().value();
        RunId run = ids.newRunId(SHARD);
        database.jdbc()
                .sql("INSERT INTO scheduler.job_versions VALUES (:id, 1, CAST(:spec AS jsonb))")
                .param("id", target)
                .param("spec", """
                        {"request":{"pathParams":{"source":"pinned"},"query":{"page":"3"},
                        "headers":{"X-Job":"yes","Authorization":"excluded"},"body":{"ok":true}}}
                        """)
                .update();
        database.jdbc()
                .sql("INSERT INTO scheduler.teams VALUES (:id, CAST(:quotas AS jsonb))")
                .param("id", team)
                .param("quotas", "{\"maxExecPerSec\":17}")
                .update();
        database.jdbc()
                .sql("INSERT INTO scheduler.api_endpoints VALUES (:id, :team, 'ACTIVE')")
                .param("id", endpoint)
                .param("team", team)
                .update();
        database.jdbc()
                .sql("INSERT INTO scheduler.api_endpoint_versions VALUES (:id, 1, CAST(:spec AS jsonb))")
                .param("id", endpoint)
                .param("spec", """
                        {"timeoutMs":2500,"defaultHeaders":{"X-Endpoint":"yes"},
                        "auth":{"secretRef":"team/api"}}
                        """)
                .update();
        insert(run, team, target, endpoint, NOW, 0, 4);

        DelayedDispatchLoader loader =
                new DelayedDispatchLoader(database.jdbc(), JsonMapper.builder().build());
        DelayedDispatchLoader.LoadedDispatch loaded = cell.transactions().execute(jdbc -> {
            DelayedRun delayed = new DelayedRunStore()
                    .claim(cell, 0, NOW).stream()
                            .filter(candidate -> candidate.run().runId().equals(run))
                            .findFirst()
                            .orElseThrow();
            return loader.load(cell, delayed);
        });
        assertThat(loaded.suspended()).isFalse();
        assertThat(loaded.maxExecPerSecond()).isEqualTo(17);
        assertThat(loaded.call().timeoutMs()).isEqualTo(2_500);
        RequestSnapshot snapshot = loaded.call().request();
        assertThat(snapshot.getPathParamsMap()).containsEntry("source", "pinned");
        assertThat(snapshot.getQueryList())
                .anySatisfy(value -> assertThat(value.getName()).isEqualTo("page"));
        assertThat(snapshot.getHeadersList())
                .extracting(dev.kairos.proto.v1.NameValue::getName)
                .contains("X-Endpoint", "X-Job")
                .doesNotContain("Authorization");
        assertThat(snapshot.getSecretRefsList()).containsExactly("team/api");
    }

    @Test
    void workflowRetryRebuildsReferencedPublicAndEncryptedInputs() {
        var jdbc = database.jdbc();
        jdbc.sql("CREATE TABLE IF NOT EXISTS scheduler.workflow_versions(workflow_id uuid,version integer,spec jsonb)")
                .update();
        jdbc.sql(
                        "CREATE TABLE IF NOT EXISTS scheduler.task_outputs(workflow_run_id uuid,team_id uuid,task_key text,outputs jsonb,sealed bytea)")
                .update();
        UUID team = ids.newTeamId().value(),
                endpoint = ids.newEndpointId().value(),
                workflow = ids.newWorkflowId(SHARD).value(),
                occurrence = ids.newWorkflowRunId(SHARD).value();
        RunId run = ids.newRunId(SHARD);
        jdbc.sql("INSERT INTO scheduler.teams VALUES (:id,'{}')")
                .param("id", team)
                .update();
        jdbc.sql("INSERT INTO scheduler.api_endpoints VALUES (:id,:team,'ACTIVE')")
                .param("id", endpoint)
                .param("team", team)
                .update();
        jdbc.sql("INSERT INTO scheduler.api_endpoint_versions VALUES (:id,1,'{\"timeoutMs\":30000}')")
                .param("id", endpoint)
                .update();
        jdbc.sql("INSERT INTO scheduler.workflow_versions VALUES (:id,1,CAST(:spec AS jsonb))")
                .param("id", workflow)
                .param(
                        "spec",
                        "{\"tasks\":{\"child\":{\"request\":{\"headers\":{\"Authorization\":\"Bearer {{ tasks.parent.outputs.token }}\"},\"body\":\"{{ tasks.parent.outputs.id }}\"}}}}")
                .update();
        jdbc.sql("INSERT INTO scheduler.task_outputs VALUES (:run,:team,'parent','{\"id\":42}',:sealed)")
                .param("run", occurrence)
                .param("team", team)
                .param(
                        "sealed",
                        "{\"v\":1,\"values\":{\"token\":\"ciphertext\"}}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .update();
        insert(run, team, workflow, endpoint, NOW, 3, 1);
        jdbc.sql(
                        "UPDATE scheduler.run_inflight SET target_type=2,target_id=:occurrence,task_key='child' WHERE run_id=:id")
                .param("occurrence", occurrence)
                .param("id", run.value())
                .update();
        var loader = new DelayedDispatchLoader(jdbc, JsonMapper.shared());
        var loaded = cell.transactions().execute(tx -> {
            var claimed = new DelayedRunStore()
                    .claim(cell, 0, NOW).stream()
                            .filter(row -> row.run().runId().equals(run))
                            .findFirst()
                            .orElseThrow();
            return loader.load(cell, claimed);
        });
        var inputs = JsonMapper.shared()
                .readTree(loaded.call().request().getSealedInputs().toByteArray());
        assertThat(inputs.path("publicTasks").path("parent").path("id").asInt()).isEqualTo(42);
        assertThat(inputs.path("tasks").path("parent").path("token").asString()).isEqualTo("ciphertext");
        assertThat(loaded.call().request().getHeadersList()).isEmpty();
    }

    @Test
    void transactionFailureRollsBackBothQueueAndOutbox() {
        RunId run = seed(NOW, 2, 2);
        try (Harness harness = harness("ACTIVE")) {
            assertThatThrownBy(() -> cell.transactions().execute(jdbc -> {
                        harness.poller().poll(cell, 0);
                        throw new IllegalStateException("injected failure");
                    }))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(state(run)).containsExactly(4, 2, 0);
        assertThat(outboxCount(run)).isZero();
    }

    @Test
    void tenThousandBackfillRunsFollowTheirDurableHundredPerSecondPace() throws Exception {
        UUID team = ids.newTeamId().value();
        UUID endpoint = ids.newEndpointId().value();
        insertBackfill(team, endpoint, 10_000);
        try (Harness harness = harness("ACTIVE")) {
            for (int second = 0; second < 100; second++) {
                poll(harness);
                long queued = database.jdbc()
                        .sql("SELECT count(*) FROM scheduler.run_inflight WHERE team_id = :team AND state = 2")
                        .param("team", team)
                        .query(Long.class)
                        .single();
                assertThat(queued).isEqualTo((second + 1L) * 100L);
                harness.clock().advance(Duration.ofSeconds(1));
            }
        }
        long dispatches =
                database.jdbc().sql("""
                SELECT count(*) FROM scheduler.outbox o
                JOIN scheduler.run_inflight r ON r.run_id = o.run_id AND r.shard_id = o.shard_id
                WHERE r.team_id = :team
                """).param("team", team).query(Long.class).single();
        assertThat(dispatches).isEqualTo(10_000);
    }

    private static Harness harness(String endpointStatus) {
        MutableClock clock = MutableClock.at(NOW);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ShardedPoller scheduler =
                new ShardedPoller(router, new OwnedShards(), "delayed-test", clock, Duration.ZERO, meters);
        CellTransactionGuard guard = new CellTransactionGuard(router);
        DelayedDispatchSource source =
                (currentCell, delayed) -> new DelayedDispatchLoader.LoadedDispatch(endpointStatus, CALL, 20_000);
        DelayedPoller poller = new DelayedPoller(
                scheduler,
                new DelayedRunStore(),
                source,
                new DelayedPacer(redis, "kairos:pace:delayed:test:" + UUID.randomUUID() + ":"),
                new OutboxWriter(guard, ids, clock),
                clock,
                Duration.ofSeconds(15),
                meters);
        return new Harness(clock, scheduler, poller);
    }

    private static void poll(Harness harness) {
        cell.transactions().execute(jdbc -> {
            harness.poller().poll(cell, 0);
            return null;
        });
    }

    private static RunId seed(Instant due, int fence, int trigger) {
        RunId run = ids.newRunId(SHARD);
        UUID target = ids.newJobId(SHARD).value();
        insert(run, ids.newTeamId().value(), target, ids.newEndpointId().value(), due, fence, trigger);
        return run;
    }

    private static void insert(RunId run, UUID team, UUID target, UUID endpoint, Instant due, int fence, int trigger) {
        database.jdbc()
                .sql("""
                INSERT INTO scheduler.run_inflight (
                  shard_id, run_id, team_id, target_type, definition_id, definition_version,
                  target_id, task_key, endpoint_id, endpoint_version, scheduled_at, due_at,
                  state, attempt, series_start, series_started_at, infra_attempt, deferrals,
                  fence, remaining_deps, priority, trigger)
                VALUES (0, :run, :team, 1, :target, 1, :target, '', :endpoint, 1,
                        :scheduled, :due, 4, 1, 1, :scheduled, 0, 0, :fence, 0, 5, :trigger)
                """)
                .param("run", run.value())
                .param("team", team)
                .param("target", target)
                .param("endpoint", endpoint)
                .param("scheduled", Timestamp.from(NOW.minusSeconds(30)))
                .param("due", Timestamp.from(due))
                .param("fence", fence)
                .param("trigger", trigger)
                .update();
    }

    private static void insertBackfill(UUID team, UUID endpoint, int count) throws Exception {
        String sql = """
                INSERT INTO scheduler.run_inflight (
                  shard_id, run_id, team_id, target_type, definition_id, definition_version,
                  target_id, task_key, endpoint_id, endpoint_version, scheduled_at, due_at,
                  state, attempt, series_start, series_started_at, infra_attempt, deferrals,
                  fence, remaining_deps, priority, trigger)
                VALUES (0, ?, ?, 1, ?, 1, ?, '', ?, 1, ?, ?, 4, 1, 1, ?, 0, 0, 0, 0, 5, 4)
                """;
        try (var connection = database.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < count; index++) {
                UUID run = ids.newRunId(SHARD).value();
                UUID target = ids.newJobId(SHARD).value();
                statement.setObject(1, run);
                statement.setObject(2, team);
                statement.setObject(3, target);
                statement.setObject(4, target);
                statement.setObject(5, endpoint);
                statement.setTimestamp(6, Timestamp.from(NOW.minusSeconds(index + 1L)));
                statement.setTimestamp(7, Timestamp.from(NOW.plusSeconds(index / 100L)));
                statement.setTimestamp(8, Timestamp.from(NOW));
                statement.addBatch();
                if ((index + 1) % 500 == 0) statement.executeBatch();
            }
            if (count % 500 != 0) statement.executeBatch();
        }
    }

    private static List<Integer> state(RunId run) {
        return database.jdbc()
                .sql("SELECT state, fence, deferrals FROM scheduler.run_inflight WHERE run_id = :run")
                .param("run", run.value())
                .query((rs, row) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3)))
                .single();
    }

    private static long outboxCount(RunId run) {
        return database.jdbc()
                .sql("SELECT count(*) FROM scheduler.outbox WHERE run_id = :run")
                .param("run", run.value())
                .query(Long.class)
                .single();
    }

    private record Harness(MutableClock clock, ShardedPoller scheduler, DelayedPoller poller) implements AutoCloseable {
        @Override
        public void close() {
            poller.close();
            scheduler.close();
        }
    }
}
