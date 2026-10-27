package dev.kairos.executor.consume.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import dev.kairos.domain.id.JobId;
import dev.kairos.domain.id.RunId;
import dev.kairos.executor.consume.application.LocalDispatchLimiter;
import dev.kairos.persistence.cell.CellRange;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.cell.CellStatus;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.TargetType;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL-backed team and endpoint concurrency deferrals. */
@KairosIntegrationTest(kafka = false, redis = false)
class JdbcDispatchAdmissionIntegrationTest {

    private static final SimpleMeterRegistry METERS = new SimpleMeterRegistry();
    private static JdbcClient control;
    private static Database cell;
    private static CellRouter cells;

    @BeforeAll
    static void createSchemas(KairosPostgres postgres) {
        Database controlDatabase =
                Database.of("control", postgres.controlOwnerDataSource(), DatabaseOptions.defaults(), METERS);
        cell = Database.of("cell-0", postgres.cellOwnerDataSource(0), DatabaseOptions.defaults(), METERS);
        control = controlDatabase.jdbc();
        control.sql("CREATE SCHEMA IF NOT EXISTS scheduler").update();
        control.sql("""
                CREATE TABLE IF NOT EXISTS scheduler.teams (
                  team_id uuid PRIMARY KEY, quotas jsonb NOT NULL)
                """).update();
        control.sql("""
                CREATE TABLE IF NOT EXISTS scheduler.api_endpoint_versions (
                  endpoint_id uuid NOT NULL, version integer NOT NULL, spec jsonb NOT NULL,
                  PRIMARY KEY (endpoint_id, version))
                """).update();
        cell.jdbc().sql("CREATE SCHEMA IF NOT EXISTS scheduler").update();
        cell.jdbc().sql("""
                CREATE TABLE IF NOT EXISTS scheduler.run_inflight (
                  shard_id smallint NOT NULL, run_id uuid PRIMARY KEY, team_id uuid NOT NULL,
                  target_id uuid NOT NULL, endpoint_id uuid NOT NULL, endpoint_version integer NOT NULL,
                  fence integer NOT NULL, state smallint NOT NULL, due_at timestamptz NOT NULL,
                  deferrals integer NOT NULL, owner_node text, lease_until timestamptz,
                  cancel_requested boolean NOT NULL)
                """).update();
        CellRange range = new CellRange(0, 0, 255, "cell-0", CellStatus.ACTIVE);
        cells = new CellRouter(() -> List.of(range), Map.of("cell-0", cell), Duration.ofMinutes(5), Clock.systemUTC());
    }

    @Test
    void overLimitTeamRunIsDurablyDeferredAndReleasedByTheDelayedPoller() {
        UUID team = TestIds.teamId().value();
        UUID endpoint = TestIds.endpointId().value();
        seedLimits(team, endpoint, 1, 10);
        Dispatch first = dispatch(team, endpoint, 4);
        Dispatch second = dispatch(team, endpoint, 4);
        seed(first);
        seed(second);
        LocalDispatchLimiter limiter = new LocalDispatchLimiter();
        JdbcDispatchAdmission admission = admission(limiter);

        assertThat(admission.acquireOrDefer(first)).isTrue();
        assertThat(admission.acquireOrDefer(second)).isFalse();
        assertDeferred(second);
        assertThat(METERS.counter("kairos.executor.deferrals", "reason", "team_inflight")
                        .count())
                .isEqualTo(1);
        admission.release(first);
    }

    @Test
    void overLimitEndpointRunIsDurablyDeferred() {
        UUID team = TestIds.teamId().value();
        UUID endpoint = TestIds.endpointId().value();
        seedLimits(team, endpoint, 10, 1);
        Dispatch first = dispatch(team, endpoint, 4);
        Dispatch second = dispatch(team, endpoint, 4);
        seed(first);
        seed(second);
        LocalDispatchLimiter limiter = new LocalDispatchLimiter();
        JdbcDispatchAdmission admission = admission(limiter);

        assertThat(admission.acquireOrDefer(first)).isTrue();
        assertThat(admission.acquireOrDefer(second)).isFalse();
        assertDeferred(second);
        assertThat(METERS.counter("kairos.executor.deferrals", "reason", "endpoint_concurrency")
                        .count())
                .isEqualTo(1);
        admission.release(first);
    }

    @Test
    void limitsAreCachedAcrossAnAdmissionBurst() {
        UUID team = TestIds.teamId().value();
        UUID endpoint = TestIds.endpointId().value();
        seedLimits(team, endpoint, 1, 10);
        Dispatch first = dispatch(team, endpoint, 4);
        Dispatch second = dispatch(team, endpoint, 4);
        seed(first);
        seed(second);
        LocalDispatchLimiter limiter = new LocalDispatchLimiter();
        JdbcDispatchAdmission admission = admission(limiter);

        assertThat(admission.acquireOrDefer(first)).isTrue();
        control.sql("UPDATE scheduler.teams SET quotas = CAST(:quotas AS jsonb) WHERE team_id = :id")
                .param("id", team)
                .param("quotas", "{\"maxInflight\":2}")
                .update();

        assertThat(admission.acquireOrDefer(second)).isFalse();
        assertDeferred(second);
        admission.release(first);
    }

    private static JdbcDispatchAdmission admission(LocalDispatchLimiter limiter) {
        return new JdbcDispatchAdmission(
                control,
                cells,
                limiter,
                new ExecutorConsumerProperties(4_000, 3_000, Duration.ofSeconds(30), Duration.ofMillis(100)),
                Clock.systemUTC(),
                METERS);
    }

    private static void seedLimits(UUID team, UUID endpoint, int teamLimit, int endpointLimit) {
        control.sql("INSERT INTO scheduler.teams VALUES (:id, CAST(:quotas AS jsonb))")
                .param("id", team)
                .param("quotas", "{\"maxInflight\":" + teamLimit + "}")
                .update();
        control.sql("INSERT INTO scheduler.api_endpoint_versions VALUES (:id, 1, CAST(:spec AS jsonb))")
                .param("id", endpoint)
                .param("spec", "{\"limits\":{\"maxConcurrency\":" + endpointLimit + "}}")
                .update();
    }

    private static Dispatch dispatch(UUID team, UUID endpoint, int shard) {
        RunId run = TestIds.runId(shard);
        JobId job = TestIds.jobId(shard);
        return Dispatch.newBuilder()
                .setRunId(bytes(run.value()))
                .setShard(shard)
                .setFence(1)
                .setAttempt(1)
                .setTeamId(bytes(team))
                .setTargetType(TargetType.TARGET_TYPE_JOB)
                .setDefinitionId(bytes(job.value()))
                .setDefinitionVersion(1)
                .setTargetId(bytes(job.value()))
                .setEndpointId(bytes(endpoint))
                .setEndpointVersion(1)
                .setPriority(9)
                .setTimeoutMs(5_000)
                .build();
    }

    private static void seed(Dispatch dispatch) {
        cell.jdbc()
                .sql("""
                        INSERT INTO scheduler.run_inflight
                        (shard_id, run_id, team_id, target_id, endpoint_id, endpoint_version, fence, state,
                         due_at, deferrals, owner_node, lease_until, cancel_requested)
                        VALUES (:shard, :run, :team, :target, :endpoint, :version, :fence, 2,
                                CURRENT_TIMESTAMP, 0, 'executor-1', CURRENT_TIMESTAMP + interval '10 seconds', false)
                        """)
                .param("shard", dispatch.getShard())
                .param("run", uuid(dispatch.getRunId()))
                .param("team", uuid(dispatch.getTeamId()))
                .param("target", uuid(dispatch.getTargetId()))
                .param("endpoint", uuid(dispatch.getEndpointId()))
                .param("version", dispatch.getEndpointVersion())
                .param("fence", dispatch.getFence())
                .update();
    }

    private static void assertDeferred(Dispatch dispatch) {
        Map<String, Object> row = cell.jdbc()
                .sql("""
                        SELECT state, deferrals, owner_node, lease_until, due_at > CURRENT_TIMESTAMP AS due_is_future
                        FROM scheduler.run_inflight WHERE run_id = :run
                        """)
                .param("run", uuid(dispatch.getRunId()))
                .query((rs, _) -> Map.<String, Object>of(
                        "state", rs.getInt("state"),
                        "deferrals", rs.getInt("deferrals"),
                        "owner_cleared", rs.getString("owner_node") == null,
                        "lease_cleared", rs.getTimestamp("lease_until") == null,
                        "future", rs.getBoolean("due_is_future")))
                .single();
        assertThat(row)
                .containsEntry("state", 4)
                .containsEntry("deferrals", 1)
                .containsEntry("owner_cleared", true)
                .containsEntry("lease_cleared", true)
                .containsEntry("future", true);
    }

    private static ByteString bytes(UUID id) {
        return ByteString.copyFrom(ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array());
    }

    private static UUID uuid(ByteString bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
