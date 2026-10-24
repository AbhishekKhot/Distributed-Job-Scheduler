package dev.kairos.watcher.singleton;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRange;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.cell.CellStatus;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Singleton coordination, endpoint protection, review timeout and purge integration tests. */
class SingletonTasksIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static PostgreSQLContainer postgres;
    private static Database controlDatabase;
    private static Database cellDatabase;
    private static Cell cell;
    private static CellRouter router;
    private static KairosIdFactory ids;
    private static SimpleMeterRegistry meters;

    @BeforeAll
    static void schema() {
        postgres = new PostgreSQLContainer(DockerImageName.parse(
                                "postgres:18.6@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336")
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("singleton")
                .withUsername("singleton")
                .withPassword("singleton");
        postgres.start();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        meters = new SimpleMeterRegistry();
        controlDatabase = Database.of(Database.CONTROL, dataSource, DatabaseOptions.defaults(), meters);
        cellDatabase = Database.of("cell-0", dataSource, DatabaseOptions.defaults(), meters);
        var jdbc = cellDatabase.jdbc();
        jdbc.sql("CREATE SCHEMA scheduler").update();
        jdbc.sql(
                        "CREATE TABLE scheduler.api_endpoints (endpoint_id uuid PRIMARY KEY, team_id uuid NOT NULL, status text NOT NULL, status_reason text, status_changed_at timestamptz, updated_at timestamptz NOT NULL, spec jsonb NOT NULL)")
                .update();
        jdbc.sql(
                        "CREATE TABLE scheduler.audit_log (audit_id uuid PRIMARY KEY, at timestamptz NOT NULL, team_id uuid, actor text NOT NULL, action text NOT NULL, resource_type text, resource_id text, details jsonb)")
                .update();
        jdbc.sql(
                        "CREATE TABLE scheduler.schedules (schedule_id uuid PRIMARY KEY, endpoint_id uuid, status smallint NOT NULL)")
                .update();
        jdbc.sql(
                        "CREATE TABLE scheduler.endpoint_health_minute (endpoint_id uuid NOT NULL, minute timestamptz NOT NULL, attempts integer NOT NULL, successes integer NOT NULL, dead_lettered integer NOT NULL)")
                .update();
        jdbc.sql(
                        "CREATE TABLE scheduler.workflow_runs (workflow_run_id uuid PRIMARY KEY, workflow_id uuid NOT NULL, workflow_version integer NOT NULL, status smallint NOT NULL, failed smallint NOT NULL, blocked_until timestamptz)")
                .update();
        jdbc.sql(
                        "CREATE TABLE scheduler.workflow_versions (workflow_id uuid NOT NULL, version integer NOT NULL, spec jsonb NOT NULL, PRIMARY KEY(workflow_id, version))")
                .update();
        jdbc.sql(
                        "CREATE TABLE scheduler.dead_letters (dlq_id uuid PRIMARY KEY, target_type smallint NOT NULL, target_id uuid NOT NULL, task_key text NOT NULL, status smallint NOT NULL, resolved_at timestamptz)")
                .update();
        jdbc.sql(
                        "CREATE TABLE scheduler.run_inflight (shard_id smallint NOT NULL, run_id uuid PRIMARY KEY, team_id uuid NOT NULL, target_type smallint NOT NULL, definition_id uuid NOT NULL, definition_version integer NOT NULL, target_id uuid NOT NULL, task_key text NOT NULL, endpoint_id uuid NOT NULL, endpoint_version integer NOT NULL, scheduled_at timestamptz NOT NULL, attempt smallint NOT NULL, infra_attempt smallint NOT NULL, trigger smallint NOT NULL, state smallint NOT NULL)")
                .update();
        jdbc.sql(
                        "CREATE TABLE scheduler.run_history (scheduled_at timestamptz NOT NULL, run_id uuid NOT NULL, attempt smallint NOT NULL, infra_attempt smallint NOT NULL, team_id uuid NOT NULL, target_type smallint NOT NULL, definition_id uuid NOT NULL, target_id uuid NOT NULL, task_key text NOT NULL, endpoint_id uuid NOT NULL, trigger smallint NOT NULL, outcome smallint NOT NULL, error_class smallint, final boolean NOT NULL, finished_at timestamptz NOT NULL)")
                .update();
        jdbc.sql("CREATE TABLE scheduler.user_sessions (session_id uuid PRIMARY KEY, expires_at timestamptz NOT NULL)")
                .update();
        jdbc.sql(
                        "CREATE TABLE scheduler.api_idempotency (team_id uuid NOT NULL, idem_key text NOT NULL, created_at timestamptz NOT NULL, PRIMARY KEY(team_id, idem_key))")
                .update();
        jdbc.sql(
                        "CREATE TABLE scheduler.jobs (job_id uuid PRIMARY KEY, status text NOT NULL, updated_at timestamptz NOT NULL)")
                .update();
        jdbc.sql("CREATE TABLE scheduler.job_health (job_id uuid PRIMARY KEY)").update();
        jdbc.sql(
                        "CREATE TABLE scheduler.workflows (workflow_id uuid PRIMARY KEY, status text NOT NULL, updated_at timestamptz NOT NULL)")
                .update();
        cell = new Cell(new CellRange(0, 0, 255, "cell-0", CellStatus.ACTIVE), cellDatabase);
        router = new CellRouter(
                () -> List.of(cell.range()),
                Map.of("cell-0", cellDatabase),
                Duration.ofMinutes(1),
                MutableClock.at(NOW));
        ids = new KairosIdFactory(MutableClock.at(NOW), new SecureRandom());
    }

    @AfterAll
    static void close() {
        postgres.close();
    }

    @BeforeEach
    void clear() {
        cellDatabase
                .jdbc()
                .sql(
                        "TRUNCATE scheduler.api_endpoints, scheduler.audit_log, scheduler.schedules, scheduler.endpoint_health_minute, scheduler.workflow_runs, scheduler.workflow_versions, scheduler.dead_letters, scheduler.run_inflight, scheduler.run_history, scheduler.user_sessions, scheduler.api_idempotency, scheduler.jobs, scheduler.job_health, scheduler.workflows")
                .update();
        meters.clear();
    }

    @Test
    void onlyOneWatcherHoldsTheDedicatedAdvisoryLock() {
        SingletonCoordinator first = new SingletonCoordinator(controlDatabase.dataSource());
        SingletonCoordinator second = new SingletonCoordinator(controlDatabase.dataSource());
        try {
            assertThat(first.isLeader()).isTrue();
            assertThat(second.isLeader()).isFalse();
            first.close();
            assertThat(second.isLeader()).isTrue();
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void autoSuspensionAuditsOnceAndHoldsActiveSchedules() {
        UUID team = UUID.randomUUID();
        UUID endpoint = endpoint(team, "{\"suspendPolicy\":{\"enabled\":true}}", NOW.minusSeconds(1_200));
        UUID optedOut = endpoint(team, "{\"suspendPolicy\":{\"enabled\":false}}", NOW.minusSeconds(1_200));
        UUID cooling = endpoint(team, "{}", NOW.minusSeconds(60));
        UUID outsideWindow = endpoint(team, "{}", NOW.minusSeconds(1_200));
        schedule(endpoint);
        schedule(optedOut);
        schedule(cooling);
        health(endpoint, 50, 20, 50);
        health(optedOut, 200, 0, 200);
        health(cooling, 200, 0, 200);
        healthAt(outsideWindow, NOW.minus(Duration.ofMinutes(5)), 50, 0, 50);

        new EndpointAutoSuspendEvaluator(
                        router,
                        controlDatabase.jdbc(),
                        new ControlTransactions(controlDatabase),
                        ids,
                        MutableClock.at(NOW),
                        meters)
                .poll();

        assertThat(endpointStatus(endpoint)).isEqualTo("SUSPENDED/AUTO_SUSPENDED");
        assertThat(endpointStatus(optedOut)).isEqualTo("ACTIVE/null");
        assertThat(endpointStatus(cooling)).isEqualTo("ACTIVE/null");
        assertThat(endpointStatus(outsideWindow)).isEqualTo("ACTIVE/null");
        assertThat(scheduleStatus(endpoint)).isEqualTo(3);
        assertThat(scheduleStatus(optedOut)).isEqualTo(1);
        assertThat(auditCount(endpoint)).isEqualTo(1);
        assertThat(auditActorAction(endpoint)).isEqualTo("system:auto-suspend/ENDPOINT_AUTO_SUSPENDED");
        assertThat(meters.counter("kairos_endpoint_suspensions_total", "endpoint_id", endpoint.toString())
                        .count())
                .isEqualTo(1);
    }

    @Test
    void reconciliationReleasesHeldSchedulesForResumedEndpoints() {
        UUID team = UUID.randomUUID();
        UUID endpoint = endpoint(team, "{}", NOW.minusSeconds(1_200));
        schedule(endpoint);
        cellDatabase
                .jdbc()
                .sql("UPDATE scheduler.schedules SET status = 3 WHERE endpoint_id = :endpoint")
                .param("endpoint", endpoint)
                .update();

        new EndpointAutoSuspendEvaluator(
                        router,
                        controlDatabase.jdbc(),
                        new ControlTransactions(controlDatabase),
                        ids,
                        MutableClock.at(NOW),
                        meters)
                .poll();

        assertThat(endpointStatus(endpoint)).isEqualTo("ACTIVE/null");
        assertThat(scheduleStatus(endpoint)).isEqualTo(1);
    }

    @Test
    void reviewTimeoutFailsOnlyDescendantsOfOpenDeadLetter() {
        UUID workflow = UUID.randomUUID();
        UUID workflowRun = UUID.randomUUID();
        cellDatabase
                .jdbc()
                .sql("INSERT INTO scheduler.workflow_runs VALUES (:run, :workflow, 1, 2, 0, :until)")
                .param("run", workflowRun)
                .param("workflow", workflow)
                .param("until", Timestamp.from(NOW.minusSeconds(1)))
                .update();
        cellDatabase
                .jdbc()
                .sql("INSERT INTO scheduler.workflow_versions VALUES (:workflow, 1, CAST(:spec AS jsonb))")
                .param("workflow", workflow)
                .param("spec", """
                        {"tasks":{"root":{"dependsOn":[]},"child":{"dependsOn":["root"]},
                        "grandchild":{"dependsOn":["child"]},"otherRoot":{"dependsOn":[]},
                        "otherChild":{"dependsOn":["otherRoot"]}}}
                        """)
                .update();
        cellDatabase
                .jdbc()
                .sql("INSERT INTO scheduler.dead_letters VALUES (:id, 2, :target, 'root', 1, NULL)")
                .param("id", UUID.randomUUID())
                .param("target", workflowRun)
                .update();
        seedWaiting(workflow, workflowRun, "child");
        seedWaiting(workflow, workflowRun, "grandchild");
        seedWaiting(workflow, workflowRun, "otherChild");

        assertThat(new ReviewTimeoutSweeper(router, JsonMapper.builder().build(), MutableClock.at(NOW)).poll())
                .isEqualTo(2);

        assertThat(activeTaskCount(workflowRun)).isEqualTo(1);
        assertThat(historyOutcomeCount(6)).isEqualTo(2);
        assertThat(workflowState(workflowRun)).containsExactly("1", "2", "null");
    }

    @Test
    void purgerDeletesInTwoThousandRowBatchesAndKeepsRecentRecords() throws Exception {
        UUID team = UUID.randomUUID();
        try (var connection = cellDatabase.dataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO scheduler.user_sessions(session_id, expires_at) VALUES (?, ?)"); ) {
            for (int i = 0; i < 2_001; i++) {
                statement.setObject(1, UUID.randomUUID());
                statement.setTimestamp(2, Timestamp.from(NOW.minusSeconds(1)));
                statement.addBatch();
            }
            statement.executeBatch();
        }
        cellDatabase
                .jdbc()
                .sql("INSERT INTO scheduler.user_sessions VALUES (:id, :expires)")
                .param("id", UUID.randomUUID())
                .param("expires", Timestamp.from(NOW.plusSeconds(3_600)))
                .update();
        cellDatabase
                .jdbc()
                .sql("INSERT INTO scheduler.api_idempotency VALUES (:team, 'old', :created), (:team, 'new', :recent)")
                .param("team", team)
                .param("created", Timestamp.from(NOW.minus(Duration.ofDays(2))))
                .param("recent", Timestamp.from(NOW.minus(Duration.ofHours(1))))
                .update();
        cellDatabase
                .jdbc()
                .sql("INSERT INTO scheduler.dead_letters VALUES (:id, 1, :target, '', 3, :resolved)")
                .param("id", UUID.randomUUID())
                .param("target", UUID.randomUUID())
                .param("resolved", Timestamp.from(NOW.minus(Duration.ofDays(8))))
                .update();
        UUID job = UUID.randomUUID();
        cellDatabase
                .jdbc()
                .sql("INSERT INTO scheduler.jobs VALUES (:id, 'DELETED', :updated)")
                .param("id", job)
                .param("updated", Timestamp.from(NOW.minus(Duration.ofDays(8))))
                .update();
        cellDatabase
                .jdbc()
                .sql("INSERT INTO scheduler.job_health VALUES (:id)")
                .param("id", job)
                .update();
        cellDatabase
                .jdbc()
                .sql("INSERT INTO scheduler.schedules VALUES (:id, NULL, 2)")
                .param("id", job)
                .update();

        RetentionPurger purger = new RetentionPurger(router, controlDatabase.jdbc(), MutableClock.at(NOW), meters);
        purger.poll();

        assertThat(count("user_sessions")).isEqualTo(1);
        assertThat(count("api_idempotency")).isEqualTo(1);
        assertThat(count("dead_letters")).isZero();
        assertThat(count("jobs")).isZero();
        assertThat(count("job_health")).isZero();
        assertThat(count("schedules")).isZero();
        assertThat(meters.counter("kairos_purger_batches_total", "table", "sessions")
                        .count())
                .isEqualTo(1);
    }

    private static UUID endpoint(UUID team, String spec, Instant changed) {
        UUID endpoint = UUID.randomUUID();
        controlDatabase
                .jdbc()
                .sql(
                        "INSERT INTO scheduler.api_endpoints VALUES (:id, :team, 'ACTIVE', NULL, :changed, :now, CAST(:spec AS jsonb))")
                .param("id", endpoint)
                .param("team", team)
                .param("changed", Timestamp.from(changed))
                .param("now", Timestamp.from(NOW))
                .param("spec", spec)
                .update();
        return endpoint;
    }

    private static void schedule(UUID endpoint) {
        cellDatabase
                .jdbc()
                .sql("INSERT INTO scheduler.schedules VALUES (:id, :endpoint, 1)")
                .param("id", UUID.randomUUID())
                .param("endpoint", endpoint)
                .update();
    }

    private static void health(UUID endpoint, int attempts, int successes, int dead) {
        healthAt(endpoint, NOW, attempts, successes, dead);
    }

    private static void healthAt(UUID endpoint, Instant at, int attempts, int successes, int dead) {
        cellDatabase
                .jdbc()
                .sql(
                        "INSERT INTO scheduler.endpoint_health_minute VALUES (:endpoint, :minute, :attempts, :successes, :dead)")
                .param("endpoint", endpoint)
                .param("minute", Timestamp.from(at.truncatedTo(ChronoUnit.MINUTES)))
                .param("attempts", attempts)
                .param("successes", successes)
                .param("dead", dead)
                .update();
    }

    private static void seedWaiting(UUID workflow, UUID workflowRun, String key) {
        cellDatabase
                .jdbc()
                .sql("""
                INSERT INTO scheduler.run_inflight
                  (shard_id, run_id, team_id, target_type, definition_id, definition_version,
                   target_id, task_key, endpoint_id, endpoint_version, scheduled_at, attempt,
                   infra_attempt, trigger, state)
                VALUES (0, :run, :team, 2, :workflow, 1, :target, :key, :endpoint, 1, :scheduled, 1, 0, 1, 1)
                """)
                .param("run", UUID.randomUUID())
                .param("team", UUID.randomUUID())
                .param("workflow", workflow)
                .param("target", workflowRun)
                .param("key", key)
                .param("endpoint", UUID.randomUUID())
                .param("scheduled", Timestamp.from(NOW.minusSeconds(60)))
                .update();
    }

    private static String endpointStatus(UUID endpoint) {
        return controlDatabase
                .jdbc()
                .sql("SELECT status, status_reason FROM scheduler.api_endpoints WHERE endpoint_id=:id")
                .param("id", endpoint)
                .query((rs, row) -> rs.getString(1) + "/" + rs.getString(2))
                .single();
    }

    private static int scheduleStatus(UUID endpoint) {
        return cellDatabase
                .jdbc()
                .sql("SELECT status FROM scheduler.schedules WHERE endpoint_id=:id")
                .param("id", endpoint)
                .query(Integer.class)
                .single();
    }

    private static long auditCount(UUID endpoint) {
        return controlDatabase
                .jdbc()
                .sql("SELECT count(*) FROM scheduler.audit_log WHERE resource_id=:id")
                .param("id", endpoint.toString())
                .query(Long.class)
                .single();
    }

    private static String auditActorAction(UUID endpoint) {
        return controlDatabase
                .jdbc()
                .sql("SELECT actor, action FROM scheduler.audit_log WHERE resource_id=:id")
                .param("id", endpoint.toString())
                .query((rs, row) -> rs.getString(1) + "/" + rs.getString(2))
                .single();
    }

    private static long activeTaskCount(UUID workflowRun) {
        return cellDatabase
                .jdbc()
                .sql("SELECT count(*) FROM scheduler.run_inflight WHERE target_id=:id")
                .param("id", workflowRun)
                .query(Long.class)
                .single();
    }

    private static long historyOutcomeCount(int outcome) {
        return cellDatabase
                .jdbc()
                .sql("SELECT count(*) FROM scheduler.run_history WHERE outcome=:outcome")
                .param("outcome", outcome)
                .query(Long.class)
                .single();
    }

    private static List<String> workflowState(UUID workflowRun) {
        return cellDatabase
                .jdbc()
                .sql("SELECT status, failed, blocked_until FROM scheduler.workflow_runs WHERE workflow_run_id=:id")
                .param("id", workflowRun)
                .query((rs, row) -> List.of(
                        Integer.toString(rs.getInt(1)),
                        Integer.toString(rs.getInt(2)),
                        rs.getTimestamp(3) == null ? "null" : rs.getTimestamp(3).toString()))
                .single();
    }

    private static long count(String table) {
        return cellDatabase
                .jdbc()
                .sql("SELECT count(*) FROM scheduler." + table)
                .query(Long.class)
                .single();
    }
}
