package dev.kairos.watcher.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.retry.RetryPlanner;
import dev.kairos.domain.run.Fence;
import dev.kairos.domain.shard.Shard;
import dev.kairos.engine.lock.AcquireResult;
import dev.kairos.engine.lock.RedisConnector;
import dev.kairos.engine.lock.RedisProperties;
import dev.kairos.engine.lock.RedisTopology;
import dev.kairos.engine.lock.RedisTopologyProbe;
import dev.kairos.engine.lock.SentMarker;
import dev.kairos.engine.outbox.CellTransactionGuard;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRange;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.cell.CellStatus;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.proto.v1.Dead;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.testsupport.KairosRedis;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.watcher.delayed.DelayedDispatchLoader;
import dev.kairos.watcher.lease.OwnedShards;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Recovery decisions and atomic writes against PostgreSQL and Redis. */
class RecoveryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final Shard SHARD = Shard.of(0);
    private static PostgreSQLContainer postgres;
    private static Database database;
    private static Cell cell;
    private static CellRouter router;
    private static RedisConnector redis;
    private static KairosIdFactory ids;

    @BeforeAll
    static void schema() {
        postgres = new PostgreSQLContainer(DockerImageName.parse(
                                "postgres:18.6@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336")
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("recovery")
                .withUsername("recovery")
                .withPassword("recovery");
        postgres.start();
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        database = Database.of("cell-0", source, DatabaseOptions.defaults(), new SimpleMeterRegistry());
        database.jdbc().sql("CREATE SCHEMA scheduler").update();
        database.jdbc().sql("""
                CREATE TABLE scheduler.run_inflight (
                  shard_id smallint NOT NULL, run_id uuid NOT NULL, team_id uuid NOT NULL,
                  target_type smallint NOT NULL, definition_id uuid NOT NULL, definition_version integer NOT NULL,
                  target_id uuid NOT NULL, task_key text NOT NULL, endpoint_id uuid NOT NULL,
                  endpoint_version integer NOT NULL, scheduled_at timestamptz NOT NULL, due_at timestamptz NOT NULL,
                  state smallint NOT NULL, attempt smallint NOT NULL, series_start smallint NOT NULL,
                  series_started_at timestamptz NOT NULL, infra_attempt smallint NOT NULL, fence integer NOT NULL,
                  priority smallint NOT NULL, trigger smallint NOT NULL, max_lateness_ms integer,
                  remaining_deps smallint NOT NULL DEFAULT 0, owner_node text, lease_until timestamptz,
                  PRIMARY KEY (shard_id, run_id))
                """).update();
        database.jdbc().sql("""
                CREATE TABLE scheduler.run_history (
                  scheduled_at timestamptz NOT NULL, run_id uuid NOT NULL, attempt smallint NOT NULL,
                  infra_attempt smallint NOT NULL, team_id uuid NOT NULL, target_type smallint NOT NULL,
                  definition_id uuid NOT NULL, target_id uuid NOT NULL, task_key text NOT NULL,
                  endpoint_id uuid NOT NULL, trigger smallint NOT NULL, outcome smallint NOT NULL,
                  error_class smallint, final boolean NOT NULL, finished_at timestamptz NOT NULL, executor_id text,
                  PRIMARY KEY (scheduled_at, run_id, attempt, infra_attempt))
                """).update();
        database.jdbc().sql("""
                CREATE TABLE scheduler.dead_letters (
                  dlq_id uuid PRIMARY KEY, team_id uuid NOT NULL, run_id uuid NOT NULL UNIQUE,
                  shard_id smallint NOT NULL, scheduled_at timestamptz NOT NULL, target_type smallint NOT NULL,
                  definition_id uuid NOT NULL, definition_version integer NOT NULL, target_id uuid NOT NULL,
                  task_key text NOT NULL, endpoint_id uuid NOT NULL, endpoint_version integer NOT NULL,
                  attempt smallint NOT NULL, infra_attempt smallint NOT NULL, priority smallint NOT NULL,
                  reason smallint NOT NULL, fence integer NOT NULL, created_at timestamptz NOT NULL)
                """).update();
        database.jdbc().sql("""
                CREATE TABLE scheduler.outbox (
                  shard_id smallint NOT NULL, outbox_id uuid NOT NULL, topic smallint NOT NULL,
                  msg_key bytea NOT NULL, payload bytea NOT NULL, trace_parent text, run_id uuid,
                  available_at timestamptz NOT NULL, created_at timestamptz NOT NULL,
                  PRIMARY KEY (shard_id, outbox_id))
                """).update();
        database.jdbc()
                .sql(
                        "CREATE TABLE scheduler.scheduler_flags (name text PRIMARY KEY, value_ts timestamptz, updated_at timestamptz NOT NULL)")
                .update();
        database.jdbc()
                .sql("CREATE TABLE scheduler.job_versions (job_id uuid, version integer, spec jsonb)")
                .update();
        database.jdbc()
                .sql("CREATE TABLE scheduler.teams (team_id uuid, quotas jsonb, retry_default jsonb)")
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
        KairosRedis container = KairosRedis.get();
        redis = RedisConnector.create(
                RedisProperties.of(container.host(), container.port(), container.username(), container.password()),
                new SimpleMeterRegistry(),
                MutableClock.at(NOW));
        redis.connect();
    }

    @AfterAll
    static void stop() {
        redis.close();
        postgres.close();
    }

    @Test
    void noMarkerRequeuesExactlyOnceWithNewInfraAttemptAndFence() throws Exception {
        RunId run = seed(3, 0, "{}");
        try (Harness harness = harness()) {
            poll(harness, false);
            poll(harness, false);
        }
        assertThat(state(run)).containsExactly(2, 1, 1, 4);
        assertThat(history(run)).containsExactly(7, 14, 0);
        Dispatch dispatch = Dispatch.parseFrom(payload(1, run));
        assertThat(dispatch.getRunId().toByteArray())
                .containsExactly(dev.kairos.proto.ProtoIds.toBytes(run.value()).toByteArray());
        assertThat(dispatch.getInfraAttempt()).isEqualTo(1);
    }

    @Test
    void presentMarkerAndReviewDeadLettersWithEvent() throws Exception {
        RunId run = seed(3, 0, "{\"unknownOutcomePolicy\":\"REVIEW\"}");
        assertThat(redis.sentMarkers().mark(new SentMarker(run, 1, 0)))
                .isEqualTo(dev.kairos.engine.lock.SentMarkerPort.MarkResult.MARKED);
        try (Harness harness = harness()) {
            poll(harness, false);
        }
        assertThat(rowCount("run_inflight", run)).isZero();
        assertThat(history(run)).containsExactly(3, 8, 1);
        assertThat(deadReason(run)).isEqualTo(8);
        Dead event = Dead.parseFrom(payload(6, run));
        assertThat(event.getRunId()).isEqualTo(dev.kairos.proto.ProtoIds.toBytes(run.value()));
    }

    @Test
    void presentMarkerAndDefaultPolicyRetriesSameRunId() {
        RunId run = seed(3, 0, "{}");
        redis.sentMarkers().mark(new SentMarker(run, 1, 0));
        try (Harness harness = harness()) {
            poll(harness, false);
        }
        assertThat(state(run)).containsExactly(4, 2, 0, 3);
        assertThat(history(run)).containsExactly(2, 8, 0);
        assertThat(rowCount("dead_letters", run)).isZero();
    }

    @Test
    void maxAgeDeadLetterPreservesUnknownOutcomeInHistory() {
        RunId run = seed(3, 0, """
                {"retryPolicy":{"maxRetries":5,"strategy":"FIXED","baseDelaySec":30,
                  "maxDelaySec":30,"maxAgeSec":60}}
                """);
        redis.sentMarkers().mark(new SentMarker(run, 1, 0));
        try (Harness harness = harness()) {
            poll(harness, false);
        }
        assertThat(deadReason(run)).isEqualTo(13);
        assertThat(history(run)).containsExactly(3, 8, 1);
    }

    @Test
    void missingMarkerInQuarantineUsesUnknownOutcomePolicy() {
        RunId run = seed(3, 0, "{\"unknownOutcomePolicy\":\"REVIEW\"}");
        try (Harness harness = harness()) {
            poll(harness, true);
        }
        assertThat(deadReason(run)).isEqualTo(8);
    }

    @Test
    void heldRunLockPreventsRecovery() {
        RunId run = seed(3, 0, "{}");
        var lockPort = redis.runLock("live-executor");
        var acquired = (AcquireResult.Acquired) lockPort.acquire(run, new Fence(3), Duration.ofMinutes(1));
        try {
            try (Harness harness = harness()) {
                poll(harness, false);
            }
            assertThat(state(run)).containsExactly(3, 1, 0, 3);
            assertThat(rowCount("run_history", run)).isZero();
        } finally {
            lockPort.release(acquired.lock());
        }
    }

    @Test
    void failedCellTransactionRollsBackHistoryAndDispatch() {
        RunId run = seed(3, 0, "{}");
        try (Harness harness = harness()) {
            assertThatThrownBy(() -> cell.transactions().execute(jdbc -> {
                        harness.lease.poll(cell, 0);
                        throw new IllegalStateException("injected rollback");
                    }))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(state(run)).containsExactly(3, 1, 0, 3);
        assertThat(rowCount("run_history", run)).isZero();
        assertThat(rowCount("outbox", run)).isZero();
    }

    @Test
    void exhaustedInfrastructureAttemptsDeadLetterExecutorLost() {
        RunId run = seed(3, 3, "{}");
        try (Harness harness = harness()) {
            poll(harness, false);
        }
        assertThat(deadReason(run)).isEqualTo(14);
    }

    @Test
    void stuckQueuedRunGetsNewFenceAndAlertMetric() throws Exception {
        RunId run = seed(2, 0, "{}");
        try (Harness harness = harness()) {
            cell.transactions().execute(jdbc -> {
                harness.stuck.poll(cell, 0);
                return null;
            });
            assertThat(harness.meters
                            .counter("kairos_stuck_queued_redispatch_total", "cell", "0")
                            .count())
                    .isEqualTo(1);
        }
        assertThat(state(run)).containsExactly(2, 1, 0, 4);
        assertThat(Dispatch.parseFrom(payload(1, run)).getFence()).isEqualTo(4);
    }

    @Test
    void replicationIdChangeSetsDurableQuarantineFlag() {
        AtomicReference<RedisTopology> topology =
                new AtomicReference<>(new RedisTopology(RedisTopology.Role.PRIMARY, "a".repeat(40), 0));
        RedisTopologyProbe probe = () -> Optional.of(topology.get());
        RedisFailoverMonitor monitor =
                new RedisFailoverMonitor(probe, database.jdbc(), MutableClock.at(NOW), new SimpleMeterRegistry());
        monitor.poll();
        assertThat(monitor.quarantined(NOW)).isFalse();
        topology.set(new RedisTopology(RedisTopology.Role.PRIMARY, "b".repeat(40), 0));
        monitor.poll();
        assertThat(monitor.quarantined(NOW)).isTrue();
        assertThat(monitor.quarantined(NOW.plusSeconds(310))).isFalse();
    }

    private static Harness harness() {
        MutableClock clock = MutableClock.at(NOW);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ShardedPoller scheduler =
                new ShardedPoller(router, new OwnedShards(), "recovery-test", clock, Duration.ZERO, meters);
        JsonMapper json = JsonMapper.builder().build();
        DelayedDispatchLoader loader = new DelayedDispatchLoader(database.jdbc(), json);
        OutboxWriter outbox = new OutboxWriter(new CellTransactionGuard(router), ids, clock);
        RedisFailoverMonitor monitor = new RedisFailoverMonitor(redis.topologyProbe(), database.jdbc(), clock, meters);
        LeaseSweeper lease = new LeaseSweeper(
                scheduler,
                new RecoveryRunStore(),
                redis.runLock("recovery-test"),
                redis.sentMarkers(),
                monitor,
                new RecoveryPolicyLoader(database.jdbc(), json),
                loader,
                new RetryPlanner(clock, java.util.concurrent.ThreadLocalRandom::current),
                ids,
                outbox,
                clock,
                meters);
        StuckQueuedSweeper stuck =
                new StuckQueuedSweeper(scheduler, new RecoveryRunStore(), loader, outbox, clock, meters);
        return new Harness(scheduler, lease, stuck, meters);
    }

    private static void poll(Harness harness, boolean quarantined) {
        if (quarantined) {
            database.jdbc()
                    .sql(
                            "INSERT INTO scheduler.scheduler_flags VALUES ('redis_marker_quarantine_until', :until, :now) ON CONFLICT (name) DO UPDATE SET value_ts = EXCLUDED.value_ts")
                    .param("until", Timestamp.from(NOW.plusSeconds(310)))
                    .param("now", Timestamp.from(NOW))
                    .update();
        } else {
            database.jdbc().sql("DELETE FROM scheduler.scheduler_flags").update();
        }
        cell.transactions().execute(jdbc -> {
            harness.lease.poll(cell, 0);
            return null;
        });
    }

    private static RunId seed(int state, int infra, String endpointSpec) {
        RunId run = ids.newRunId(SHARD);
        UUID target = ids.newJobId(SHARD).value();
        UUID team = ids.newTeamId().value();
        UUID endpoint = ids.newEndpointId().value();
        database.jdbc()
                .sql("INSERT INTO scheduler.job_versions VALUES (:id, 1, '{}'::jsonb)")
                .param("id", target)
                .update();
        database.jdbc()
                .sql("INSERT INTO scheduler.teams VALUES (:id, '{}'::jsonb, NULL)")
                .param("id", team)
                .update();
        database.jdbc()
                .sql("INSERT INTO scheduler.api_endpoints VALUES (:id, :team, 'ACTIVE')")
                .param("id", endpoint)
                .param("team", team)
                .update();
        database.jdbc()
                .sql("INSERT INTO scheduler.api_endpoint_versions VALUES (:id, 1, CAST(:spec AS jsonb))")
                .param("id", endpoint)
                .param("spec", endpointSpec)
                .update();
        database.jdbc()
                .sql("""
                INSERT INTO scheduler.run_inflight (shard_id, run_id, team_id, target_type, definition_id,
                    definition_version, target_id, task_key, endpoint_id, endpoint_version, scheduled_at, due_at,
                    state, attempt, series_start, series_started_at, infra_attempt, fence, priority, trigger,
                    owner_node, lease_until)
                VALUES (0, :run, :team, 1, :target, 1, :target, '', :endpoint, 1, :scheduled, :due,
                    :state, 1, 1, :series, :infra, 3, 5, 1, 'executor-a', :lease)
                """)
                .param("run", run.value())
                .param("team", team)
                .param("target", target)
                .param("endpoint", endpoint)
                .param("scheduled", Timestamp.from(NOW.minusSeconds(900)))
                .param("due", Timestamp.from(NOW.minusSeconds(700)))
                .param("state", state)
                .param("series", Timestamp.from(NOW.minusSeconds(100)))
                .param("infra", infra)
                .param("lease", Timestamp.from(NOW.minusSeconds(40)))
                .update();
        return run;
    }

    private static List<Integer> state(RunId run) {
        return database.jdbc()
                .sql("SELECT state, attempt, infra_attempt, fence FROM scheduler.run_inflight WHERE run_id = :run")
                .param("run", run.value())
                .query((rs, row) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)))
                .single();
    }

    private static List<Integer> history(RunId run) {
        return database.jdbc()
                .sql("SELECT outcome, error_class, final FROM scheduler.run_history WHERE run_id = :run")
                .param("run", run.value())
                .query((rs, row) -> List.of(rs.getInt(1), rs.getInt(2), rs.getBoolean(3) ? 1 : 0))
                .single();
    }

    private static int deadReason(RunId run) {
        return database.jdbc()
                .sql("SELECT reason FROM scheduler.dead_letters WHERE run_id = :run")
                .param("run", run.value())
                .query(Integer.class)
                .single();
    }

    private static long rowCount(String table, RunId run) {
        return database.jdbc()
                .sql("SELECT count(*) FROM scheduler." + table + " WHERE run_id = :run")
                .param("run", run.value())
                .query(Long.class)
                .single();
    }

    private static byte[] payload(int topic, RunId run) {
        if (topic == 6) {
            return database.jdbc()
                    .sql("SELECT payload FROM scheduler.outbox WHERE topic = :topic AND msg_key = :key")
                    .param("topic", topic)
                    .param(
                            "key",
                            dev.kairos.proto.ProtoIds.toBytes(database.jdbc()
                                            .sql("SELECT definition_id FROM scheduler.dead_letters WHERE run_id = :run")
                                            .param("run", run.value())
                                            .query(UUID.class)
                                            .single())
                                    .toByteArray())
                    .query(byte[].class)
                    .single();
        }
        return database.jdbc()
                .sql("SELECT payload FROM scheduler.outbox WHERE run_id = :run")
                .param("run", run.value())
                .query(byte[].class)
                .single();
    }

    private record Harness(
            ShardedPoller scheduler, LeaseSweeper lease, StuckQueuedSweeper stuck, SimpleMeterRegistry meters)
            implements AutoCloseable {
        @Override
        public void close() {
            lease.close();
            stuck.close();
            scheduler.close();
        }
    }
}
