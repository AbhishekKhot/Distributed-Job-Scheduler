package dev.kairos.api.dlq;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.domain.id.JobId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.testsupport.TestIds;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP coverage of dead-letter listing, team isolation, acknowledgement, and discard. */
class DlqIntegrationTest extends ApiApplicationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void filtersListsGetsAndKeepsEntriesTeamScoped() throws Exception {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);
        UUID open = insertDlq(team, 1);
        UUID acknowledged = insertDlq(team, 130);
        ApiTestFixtures.cell(1)
                .sql("UPDATE scheduler.dead_letters SET status=2, assignee='ops' WHERE dlq_id=:id")
                .param("id", acknowledged)
                .update();

        JsonNode page = json.readTree(
                send("GET", "/v1/dlq?status=OPEN", caller.token(), null).body());
        assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("items").path(0).path("dlqId").asString()).isEqualTo(open.toString());
        assertThat(json.readTree(send("GET", "/v1/dlq/" + open, caller.token(), null)
                                .body())
                        .path("status")
                        .asString())
                .isEqualTo("OPEN");
        assertThat(send(
                                "GET",
                                "/v1/dlq/" + open,
                                newUser(ApiTestFixtures.insertTeam()).token(),
                                null)
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    void acknowledgesAndDiscardsOnlyUnresolvedEntries() throws Exception {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);
        UUID acknowledged = insertDlq(team, 3);
        JsonNode ack = json.readTree(send(
                        "POST",
                        "/v1/dlq/" + acknowledged + "/acknowledge",
                        caller.token(),
                        "{\"assignee\":\"on-call\",\"notes\":\"investigating\"}")
                .body());
        assertThat(ack.path("status").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(ack.path("assignee").asString()).isEqualTo("on-call");

        UUID discarded = insertDlq(team, 4);
        JsonNode result = json.readTree(
                send("POST", "/v1/dlq/" + discarded + "/discard", caller.token(), "{\"reason\":\"bad input\"}")
                        .body());
        assertThat(result.path("status").asString()).isEqualTo("DISCARDED");
        assertThat(result.path("resolvedAt").isString()).isTrue();
        assertThat(send("POST", "/v1/dlq/" + discarded + "/discard", caller.token(), "{\"reason\":\"again\"}")
                        .statusCode())
                .isEqualTo(409);
    }

    @Test
    void redrivesOnceWithTheSameRunIdAndContinuedFence() throws Exception {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);
        UUID id = insertDlq(team, 5);
        UUID endpoint = TestIds.endpointId().value();
        JobId job = new JobId(ApiTestFixtures.cell(0)
                .sql("SELECT definition_id FROM scheduler.dead_letters WHERE dlq_id=:id")
                .param("id", id)
                .query(UUID.class)
                .single());
        UUID run = ApiTestFixtures.cell(0)
                .sql("SELECT run_id FROM scheduler.dead_letters WHERE dlq_id=:id")
                .param("id", id)
                .query(UUID.class)
                .single();
        Instant now = Instant.now();
        String endpointSpec = "{\"timeoutMs\":10000,\"defaultHeaders\":{},\"auth\":{\"type\":\"NONE\"}}";
        ApiTestFixtures.control()
                .sql("""
                        INSERT INTO scheduler.api_endpoints
                            (endpoint_id, team_id, name, version, spec, created_at, updated_at)
                        VALUES (:id, :team, :name, 1, CAST(:spec AS jsonb), :now, :now)
                        """)
                .param("id", endpoint)
                .param("team", team.value())
                .param("name", "redrive-" + id.toString().substring(0, 8))
                .param("spec", endpointSpec)
                .param("now", ApiTestFixtures.utc(now))
                .update();
        ApiTestFixtures.control()
                .sql("""
                        INSERT INTO scheduler.api_endpoint_versions (endpoint_id, version, spec, created_at, created_by)
                        VALUES (:id, 1, CAST(:spec AS jsonb), :now, 'test')
                        """)
                .param("id", endpoint)
                .param("spec", endpointSpec)
                .param("now", ApiTestFixtures.utc(now))
                .update();
        String jobSpec = "{\"endpoint\":\"redrive-" + id.toString().substring(0, 8)
                + "\",\"request\":{\"body\":{\"ok\":true}},\"priority\":5}";
        ApiTestFixtures.cell(0)
                .sql("""
                        INSERT INTO scheduler.jobs (job_id, team_id, name, version, endpoint_id, spec, created_by, created_at, updated_at)
                        VALUES (:job, :team, :name, 1, :endpoint, CAST(:spec AS jsonb), 'user:test', :now, :now)
                        """)
                .param("job", job.value())
                .param("team", team.value())
                .param("name", "redrive-" + id.toString().substring(0, 8))
                .param("endpoint", endpoint)
                .param("spec", jobSpec)
                .param("now", ApiTestFixtures.utc(now))
                .update();
        ApiTestFixtures.cell(0)
                .sql(
                        "INSERT INTO scheduler.job_versions (job_id, version, spec, created_at) VALUES (:job, 1, CAST(:spec AS jsonb), :now)")
                .param("job", job.value())
                .param("spec", jobSpec)
                .param("now", ApiTestFixtures.utc(now))
                .update();
        ApiTestFixtures.cell(0)
                .sql("UPDATE scheduler.dead_letters SET endpoint_id=:endpoint, fence=8 WHERE dlq_id=:id")
                .param("endpoint", endpoint)
                .param("id", id)
                .update();

        var response = send("POST", "/v1/dlq/" + id + "/redrive", caller.token(), "{\"mode\":\"ORIGINAL\"}");
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(json.readTree(response.body()).path("status").asString()).isEqualTo("REDRIVEN");
        assertThat(ApiTestFixtures.cell(0)
                        .sql(
                                "SELECT attempt, series_start, fence, state, trigger FROM scheduler.run_inflight WHERE run_id=:run")
                        .param("run", run)
                        .query((rs, row) ->
                                java.util.List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)))
                        .single())
                .containsExactly(3, 3, 8, 4, 5);
        assertThat(send("POST", "/v1/dlq/" + id + "/redrive", caller.token(), "{\"mode\":\"ORIGINAL\"}")
                        .statusCode())
                .isEqualTo(409);
    }

    @Test
    void bulkDryRunRequiresAFilterAndIsIdempotent() throws Exception {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);
        insertDlq(team, 6);
        insertDlq(team, 7);
        assertThat(send(
                                "POST",
                                "/v1/dlq:redrive",
                                caller.token(),
                                "{\"filter\":{},\"mode\":\"ORIGINAL\",\"dryRun\":true}",
                                "Idempotency-Key",
                                "bulk-empty")
                        .statusCode())
                .isEqualTo(400);
        String request = "{\"filter\":{\"reason\":\"TIMEOUT\"},\"mode\":\"ORIGINAL\",\"dryRun\":true}";
        var first = send("POST", "/v1/dlq:redrive", caller.token(), request, "Idempotency-Key", "bulk-dry-run");
        var replay = send("POST", "/v1/dlq:redrive", caller.token(), request, "Idempotency-Key", "bulk-dry-run");
        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(replay.body()).isEqualTo(first.body());
        JsonNode result = json.readTree(first.body());
        assertThat(result.path("matched").asInt()).isEqualTo(2);
        assertThat(result.path("scheduled").asInt()).isZero();
        assertThat(result.path("dryRun").asBoolean()).isTrue();
    }

    private static UUID insertDlq(TeamId team, int shard) {
        UUID id = TestIds.dlqId(shard).value();
        UUID run = TestIds.runId(shard).value();
        Instant now = Instant.now().minusSeconds(shard);
        ApiTestFixtures.cell(shard / 128)
                .sql("""
                        INSERT INTO scheduler.dead_letters
                            (dlq_id, team_id, run_id, shard_id, scheduled_at, target_type, definition_id,
                             definition_version, target_id, endpoint_id, endpoint_version, attempt, infra_attempt,
                             priority, reason, last_error, status, fence, created_at)
                        VALUES (:id, :team, :run, :shard, :now, 1, :job, 1, :job, :endpoint, 1, 2, 0, 5, 1,
                                'failed', 1, 7, :now)
                        """)
                .param("id", id)
                .param("team", team.value())
                .param("run", run)
                .param("shard", shard)
                .param("now", ApiTestFixtures.utc(now))
                .param("job", TestIds.jobId(shard).value())
                .param("endpoint", TestIds.endpointId().value())
                .update();
        return id;
    }
}
