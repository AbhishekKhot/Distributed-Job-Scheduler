package dev.kairos.api.endpoints;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.endpoints.application.EndpointStore;
import dev.kairos.api.endpoints.application.EndpointTestGateway;
import dev.kairos.persistence.cell.CellRouter;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real endpoint CRUD requests against the control database and the shared authentication chain. */
class EndpointsIntegrationTest extends ApiApplicationTest {
    private static final String REQUEST = """
            {
              "name":"orders-create",
              "method":"POST",
              "urlTemplate":"https://orders.internal/v1/{orderId}",
              "defaultHeaders":{"X-Source":"kairos"},
              "auth":{"type":"NONE"},
              "caBundle":null,
              "timeoutMs":30000,
              "successStatus":["2xx"],
              "statusOverrides":{},
              "retryPolicy":{"maxRetries":3,"strategy":"FULL_JITTER","baseDelaySec":1,"maxDelaySec":10,"maxAgeSec":600},
              "limits":{"maxConcurrency":10,"ratePerSec":20},
              "outputs":{},
              "unknownOutcomePolicy":"RETRY_WITH_IDEMPOTENCY_KEY",
              "suspendPolicy":{"enabled":true}
            }
            """;

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private EndpointStore endpoints;

    @Autowired
    @Qualifier("controlJdbcClient") private JdbcClient control;

    @Autowired
    private CellRouter cells;

    @MockitoBean
    private EndpointTestGateway testGateway;

    @Test
    void createsListsReadsUpdatesWithIfMatchAndDeletesImmutableVersions() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        HttpResponse<String> created = send("POST", "/v1/endpoints", user.token(), REQUEST);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("ETag")).contains("\"1\"");
        JsonNode body = json.readTree(created.body());
        UUID id = UUID.fromString(body.path("id").asString());
        assertThat(body.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.path("version").asInt()).isEqualTo(1);

        assertThat(send("GET", "/v1/endpoints/" + id, user.token(), null)
                        .headers()
                        .firstValue("ETag"))
                .contains("\"1\"");
        assertThat(json.readTree(
                                send("GET", "/v1/endpoints", user.token(), null).body())
                        .path("items")
                        .path(0)
                        .path("id")
                        .asString())
                .isEqualTo(id.toString());
        assertThat(send("PUT", "/v1/endpoints/" + id, user.token(), REQUEST).statusCode())
                .isEqualTo(428);

        String updatedRequest = REQUEST.replace("30000", "45000");
        HttpResponse<String> updated =
                send("PUT", "/v1/endpoints/" + id, user.token(), updatedRequest, "If-Match", "\"1\"");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.headers().firstValue("ETag")).contains("\"2\"");
        assertThat(json.readTree(updated.body()).path("timeoutMs").asInt()).isEqualTo(45000);
        assertThat(send("PUT", "/v1/endpoints/" + id, user.token(), REQUEST, "If-Match", "\"1\"")
                        .statusCode())
                .isEqualTo(412);
        assertThat(control.sql("SELECT count(*) FROM scheduler.api_endpoint_versions WHERE endpoint_id = :id")
                        .param("id", id)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);

        assertThat(send("DELETE", "/v1/endpoints/" + id, user.token(), null).statusCode())
                .isEqualTo(204);
        assertThat(control.sql("SELECT count(*) FROM scheduler.api_endpoint_versions WHERE endpoint_id = :id")
                        .param("id", id)
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(control.sql("SELECT count(*) FROM scheduler.audit_log WHERE team_id = :team AND resource_id = :id")
                        .param("team", user.team().value())
                        .param("id", id.toString())
                        .query(Integer.class)
                        .single())
                .isEqualTo(3);
    }

    @Test
    void rejectsDuplicateNamesAndDeletionWhileAnyCellJobUsesTheEndpoint() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        HttpResponse<String> created = send("POST", "/v1/endpoints", user.token(), REQUEST);
        UUID id = UUID.fromString(json.readTree(created.body()).path("id").asString());
        assertThat(send("POST", "/v1/endpoints", user.token(), REQUEST).statusCode())
                .isEqualTo(409);

        var cell = cells.allCells().getFirst();
        cell.jdbc()
                .sql("""
                INSERT INTO scheduler.jobs
                    (job_id, team_id, name, version, endpoint_id, spec, created_by, created_at, updated_at)
                VALUES (:job, :team, 'endpoint-in-use', 1, :endpoint, '{}'::jsonb, 'user:test', :now, :now)
                """)
                .param("job", UUID.randomUUID())
                .param("team", user.team().value())
                .param("endpoint", id)
                .param("now", ApiTestFixtures.utc(Instant.now()))
                .update();
        HttpResponse<String> blocked = send("DELETE", "/v1/endpoints/" + id, user.token(), null);
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(json.readTree(blocked.body()).path("type").asString()).isEqualTo("/errors/endpoint-in-use");
        cell.jdbc()
                .sql("UPDATE scheduler.jobs SET status = 'DELETED' WHERE endpoint_id = :id")
                .param("id", id)
                .update();
        assertThat(send("DELETE", "/v1/endpoints/" + id, user.token(), null).statusCode())
                .isEqualTo(204);
    }

    @Test
    void rejectsUnsafeTargetsAndMissingCredentialReferences() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        assertThat(send(
                                "POST",
                                "/v1/endpoints",
                                user.token(),
                                REQUEST.replace("https://orders.internal", "http://orders.internal"))
                        .statusCode())
                .isEqualTo(400);
        HttpResponse<String> metadata = send(
                "POST",
                "/v1/endpoints",
                user.token(),
                REQUEST.replace("https://orders.internal/v1/{orderId}", "https://169.254.169.254/latest/meta-data"));
        assertThat(metadata.statusCode()).isEqualTo(400);
        assertThat(json.readTree(metadata.body()).path("type").asString()).isEqualTo("/errors/target-not-allowed");
        HttpResponse<String> secret = send(
                "POST",
                "/v1/endpoints",
                user.token(),
                REQUEST.replace("\"type\":\"NONE\"", "\"type\":\"BEARER\",\"secretRef\":\"missing-token\""));
        assertThat(secret.statusCode()).isEqualTo(400);
        assertThat(json.readTree(secret.body()).path("type").asString()).isEqualTo("/errors/secret-not-found");
    }

    @Test
    void endpointIdentifiersAreTeamScoped() {
        Caller owner = newUser(ApiTestFixtures.insertTeam());
        Caller other = newUser(ApiTestFixtures.insertTeam());
        HttpResponse<String> created = send("POST", "/v1/endpoints", owner.token(), REQUEST);
        UUID id = UUID.fromString(json.readTree(created.body()).path("id").asString());

        assertThat(send("GET", "/v1/endpoints/" + id, other.token(), null).statusCode())
                .isEqualTo(404);
        assertThat(endpoints.find(owner.team(), id).name()).isEqualTo("orders-create");
        assertThat(endpoints.list(other.team(), null, null, 10)).isEmpty();
    }

    @Test
    void endpointPagesAreCursorAndStatusBound() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        send("POST", "/v1/endpoints", user.token(), REQUEST);
        send("POST", "/v1/endpoints", user.token(), REQUEST.replace("orders-create", "inventory-read"));

        JsonNode first = json.readTree(send("GET", "/v1/endpoints?limit=1&status=ACTIVE", user.token(), null)
                .body());
        assertThat(first.path("items").size()).isEqualTo(1);
        String cursor = first.path("nextCursor").asString();
        JsonNode second =
                json.readTree(send("GET", "/v1/endpoints?limit=1&status=ACTIVE&cursor=" + cursor, user.token(), null)
                        .body());
        assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(send("GET", "/v1/endpoints?status=INVALID", user.token(), null)
                        .statusCode())
                .isEqualTo(400);
        assertThat(send("GET", "/v1/endpoints?limit=1&status=SUSPENDED&cursor=" + cursor, user.token(), null)
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void validatesCaBundleReferenceAndKeepsReferencedBundle() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        HttpResponse<String> missing = send(
                "POST",
                "/v1/endpoints",
                user.token(),
                REQUEST.replace("\"caBundle\":null", "\"caBundle\":\"corp-ca\""));
        assertThat(missing.statusCode()).isEqualTo(400);

        control.sql("""
                INSERT INTO scheduler.team_ca_bundles (team_id, name, pem, created_at)
                VALUES (:team, 'corp-ca', 'test-certificate', :now)
                """)
                .param("team", user.team().value())
                .param("now", ApiTestFixtures.utc(Instant.now()))
                .update();
        HttpResponse<String> created = send(
                "POST",
                "/v1/endpoints",
                user.token(),
                REQUEST.replace("\"caBundle\":null", "\"caBundle\":\"corp-ca\""));
        assertThat(created.statusCode()).isEqualTo(201);
        HttpResponse<String> blocked = send("DELETE", "/v1/ca-bundles/corp-ca", user.token(), null);
        assertThat(blocked.statusCode()).isEqualTo(409);
    }

    @Test
    void testCallUsesExecutorGatewayAndRejectsMissingPathParametersBeforeCallingIt() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        HttpResponse<String> created = send("POST", "/v1/endpoints", user.token(), REQUEST);
        UUID id = UUID.fromString(json.readTree(created.body()).path("id").asString());
        when(testGateway.test(any(), any(), any()))
                .thenReturn(new EndpointTestGateway.Result(201, 9, "SUCCESS", Map.of("orderId", "<redacted>"), ""));

        HttpResponse<String> missing = send("POST", "/v1/endpoints/" + id + ":test", user.token(), "{}");
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(json.readTree(missing.body()).path("type").asString()).isEqualTo("/errors/render-failed");

        HttpResponse<String> tested =
                send("POST", "/v1/endpoints/" + id + ":test", user.token(), "{\"pathParams\":{\"orderId\":\"42\"}}");
        assertThat(tested.statusCode()).isEqualTo(200);
        assertThat(json.readTree(tested.body()).path("outputs").path("orderId").asString())
                .isEqualTo("<redacted>");
        for (int attempt = 1; attempt < 10; attempt++) {
            assertThat(send(
                                    "POST",
                                    "/v1/endpoints/" + id + ":test",
                                    user.token(),
                                    "{\"pathParams\":{\"orderId\":\"42\"}}")
                            .statusCode())
                    .isEqualTo(200);
        }
        HttpResponse<String> limited =
                send("POST", "/v1/endpoints/" + id + ":test", user.token(), "{\"pathParams\":{\"orderId\":\"42\"}}");
        assertThat(limited.statusCode()).isEqualTo(429);
    }

    @Test
    void suspendAndResumeReconcileSchedulesAcrossEveryCell() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        UUID id = UUID.fromString(json.readTree(
                        send("POST", "/v1/endpoints", user.token(), REQUEST).body())
                .path("id")
                .asString());
        Instant missedAt = Instant.now().minusSeconds(3600);
        for (var cell : cells.allCells()) {
            cell.jdbc()
                    .sql("""
                    INSERT INTO scheduler.schedules
                        (schedule_id, target_type, team_id, endpoint_id, shard_id, status, next_fire_at, kind,
                         dst_policy, misfire_policy, definition_version)
                    VALUES (:schedule, 1, :team, :endpoint, :shard, 1, :fire, 1, 1, 2, 1)
                    """)
                    .param("schedule", UUID.randomUUID())
                    .param("team", user.team().value())
                    .param("endpoint", id)
                    .param("shard", cell.range().shardFrom())
                    .param("fire", ApiTestFixtures.utc(missedAt))
                    .update();
        }

        HttpResponse<String> suspended =
                send("POST", "/v1/endpoints/" + id + "/suspend", user.token(), "{\"reason\":\"maintenance\"}");
        assertThat(suspended.statusCode()).isEqualTo(200);
        assertThat(json.readTree(suspended.body()).path("status").asString()).isEqualTo("SUSPENDED");
        for (var cell : cells.allCells()) {
            assertThat(cell.jdbc()
                            .sql("SELECT count(*) FROM scheduler.schedules WHERE endpoint_id = :id AND status = 3")
                            .param("id", id)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        }
        assertThat(send("POST", "/v1/endpoints/" + id + "/suspend", user.token(), "{}")
                        .statusCode())
                .isEqualTo(409);

        HttpResponse<String> resumed = send("POST", "/v1/endpoints/" + id + "/resume", user.token(), null);
        assertThat(resumed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(resumed.body()).path("status").asString()).isEqualTo("ACTIVE");
        for (var cell : cells.allCells()) {
            assertThat(cell.jdbc()
                            .sql("SELECT count(*) FROM scheduler.schedules WHERE endpoint_id = :id AND status = 1")
                            .param("id", id)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        }
        assertThat(send("POST", "/v1/endpoints/" + id + "/resume", user.token(), null)
                        .statusCode())
                .isEqualTo(409);
    }

    @Test
    void healthAggregatesCellCountersAndValidatesWindowBounds() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        UUID id = UUID.fromString(json.readTree(
                        send("POST", "/v1/endpoints", user.token(), REQUEST).body())
                .path("id")
                .asString());
        JsonNode health = json.readTree(send("GET", "/v1/endpoints/" + id + "/health?window=5m", user.token(), null)
                .body());
        assertThat(health.path("window").asString()).isEqualTo("5m");
        assertThat(health.path("attempts").asLong()).isZero();
        assertThat(health.path("successes").asLong()).isZero();
        assertThat(health.path("failureRatio").asDouble()).isZero();
        assertThat(send("GET", "/v1/endpoints/" + id + "/health?window=4m", user.token(), null)
                        .statusCode())
                .isEqualTo(400);
        assertThat(send("GET", "/v1/endpoints/" + id + "/health?window=25h", user.token(), null)
                        .statusCode())
                .isEqualTo(400);
    }
}
