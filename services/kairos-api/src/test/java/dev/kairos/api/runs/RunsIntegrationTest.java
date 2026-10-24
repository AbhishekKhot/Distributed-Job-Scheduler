package dev.kairos.api.runs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.domain.id.JobId;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Cancel;
import dev.kairos.testsupport.TestIds;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP coverage of run listing, details, team isolation and transactional cancellation. */
class RunsIntegrationTest extends ApiApplicationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void listsAndGetsInflightRunsWithTeamBoundPaginationAndAttempts() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);
        RunId older = insertInflight(team, 2, Instant.now().minusSeconds(5), 2);
        RunId newer = insertInflight(team, 2, Instant.now().minusSeconds(2), 130);
        ApiTestFixtures.cell(0)
                .sql("""
                        INSERT INTO scheduler.run_history
                            (scheduled_at, run_id, attempt, infra_attempt, team_id, target_type, definition_id,
                             target_id, task_key, endpoint_id, trigger, outcome, error_class, final, finished_at,
                             http_status, latency_ms)
                        VALUES (:scheduled, :run, 1, 0, :team, 1, :job, :job, '', :endpoint, 3, 2, 7, false,
                                :finished, 503, 12)
                        """)
                .param(
                        "scheduled",
                        ApiTestFixtures.utc(Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS)))
                .param("run", older.value())
                .param("team", team.value())
                .param("job", jobFor(older).value())
                .param("endpoint", TestIds.endpointId().value())
                .param("finished", ApiTestFixtures.utc(Instant.now()))
                .update();

        JsonNode page = json.readTree(
                send("GET", "/v1/runs?limit=1", caller.token(), null).body());
        assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("items").path(0).path("runId").asString()).isEqualTo(newer.toString());
        String cursor = page.path("nextCursor").asString();
        JsonNode second = json.readTree(
                send("GET", "/v1/runs?cursor=" + cursor, caller.token(), null).body());
        assertThat(second.path("items").path(0).path("runId").asString()).isEqualTo(older.toString());
        assertThat(send(
                                "GET",
                                "/v1/runs?cursor=" + cursor,
                                newUser(ApiTestFixtures.insertTeam()).token(),
                                null)
                        .statusCode())
                .isEqualTo(400);

        JsonNode detail = json.readTree(
                send("GET", "/v1/runs/" + older, caller.token(), null).body());
        assertThat(detail.path("status").asString()).isEqualTo("QUEUED");
        assertThat(detail.path("attempts").path(0).path("outcome").asString()).isEqualTo("FAILED_RETRYING");
        assertThat(detail.path("attempts").path(0).path("httpStatus").asInt()).isEqualTo(503);
    }

    @Test
    void cancellingPendingRunDeletesDispatchAndWritesFinalHistory() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);
        RunId run = insertInflight(team, 2, Instant.now());
        Instant now = Instant.now();
        ApiTestFixtures.cell(0)
                .sql("""
                        INSERT INTO scheduler.outbox
                            (shard_id, outbox_id, topic, msg_key, payload, run_id, available_at, created_at)
                        VALUES (0, :id, 1, :key, :payload, :run, :now, :now)
                        """)
                .param("id", TestIds.id(0))
                .param("key", ProtoIds.toBytes(jobFor(run).value()).toByteArray())
                .param("payload", new byte[] {1})
                .param("run", run.value())
                .param("now", ApiTestFixtures.utc(now))
                .update();

        JsonNode result = json.readTree(send("POST", "/v1/runs/" + run + "/cancel", caller.token(), null)
                .body());
        assertThat(result.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(rowCount("run_inflight", run.value())).isZero();
        assertThat(rowCount("outbox", run.value())).isZero();
        assertThat(ApiTestFixtures.cell(0)
                        .sql("SELECT outcome FROM scheduler.run_history WHERE run_id = :run AND final")
                        .param("run", run.value())
                        .query(Integer.class)
                        .single())
                .isEqualTo(4);
    }

    @Test
    void cancellingRunningRunFlagsItAndWritesTheFencedCancelOutboxMessage() throws Exception {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);
        RunId run = insertInflight(team, 3, Instant.now());

        JsonNode result = json.readTree(send("POST", "/v1/runs/" + run + "/cancel", caller.token(), null)
                .body());
        assertThat(result.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(ApiTestFixtures.cell(0)
                        .sql("SELECT cancel_requested FROM scheduler.run_inflight WHERE run_id=:run")
                        .param("run", run.value())
                        .query(Boolean.class)
                        .single())
                .isTrue();
        byte[] payload = ApiTestFixtures.cell(0)
                .sql("SELECT payload FROM scheduler.outbox WHERE run_id=:run AND topic=4")
                .param("run", run.value())
                .query(byte[].class)
                .single();
        Cancel message = Cancel.parseFrom(payload);
        assertThat(ProtoIds.toUuid(message.getRunId())).isEqualTo(run.value());
        assertThat(message.getFence()).isEqualTo(1);
    }

    private static RunId insertInflight(TeamId team, int state, Instant scheduledAt) {
        return insertInflight(team, state, scheduledAt, 0);
    }

    private static RunId insertInflight(TeamId team, int state, Instant scheduledAt, int shard) {
        RunId run = TestIds.runId(shard);
        JobId job = jobFor(run);
        Instant now = scheduledAt.truncatedTo(ChronoUnit.MICROS);
        ApiTestFixtures.cell(shard / 128)
                .sql("""
                        INSERT INTO scheduler.run_inflight
                            (shard_id, run_id, team_id, target_type, definition_id, definition_version, target_id,
                             task_key, endpoint_id, endpoint_version, scheduled_at, due_at, state, attempt, series_start,
                             series_started_at, fence, priority, trigger)
                        VALUES (:shard, :run, :team, 1, :job, 1, :job, '', :endpoint, 1, :scheduled, :due, :state, 1, 1,
                                :started, 1, 7, 3)
                        """)
                .param("run", run.value())
                .param("shard", shard)
                .param("team", team.value())
                .param("job", job.value())
                .param("endpoint", TestIds.endpointId().value())
                .param("scheduled", ApiTestFixtures.utc(now))
                .param("due", ApiTestFixtures.utc(now))
                .param("state", state)
                .param("started", ApiTestFixtures.utc(now))
                .update();
        return run;
    }

    private static JobId jobFor(RunId run) {
        return new JobId(UUID.fromString(run.value().toString().substring(0, 36)));
    }

    private static long rowCount(String table, UUID runId) {
        return ApiTestFixtures.cell(0)
                .sql("SELECT count(*) FROM scheduler." + table + " WHERE run_id=:run")
                .param("run", runId)
                .query(Long.class)
                .single();
    }
}
