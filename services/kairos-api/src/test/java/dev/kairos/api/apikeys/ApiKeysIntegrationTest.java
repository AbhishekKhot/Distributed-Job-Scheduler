package dev.kairos.api.apikeys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.apikeys.application.ApiKeyStore;
import dev.kairos.api.apikeys.infrastructure.JdbcApiKeyStore;
import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.support.paging.CursorCodec;
import dev.kairos.domain.id.TeamId;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.apikey.ApiKeyService;
import dev.kairos.security.apikey.IssuedApiKey;
import dev.kairos.testsupport.MutableClock;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** LLD APIs 13–15 over real HTTP, Postgres and Redis, including cache expiry without sleeps. */
@Import(ApiKeysIntegrationTest.TestClock.class)
class ApiKeysIntegrationTest extends ApiApplicationTest {
    private static final String PATH = "/v1/api-keys";
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private MutableClock clock;

    @Autowired
    private ApiKeyService keys;

    @Autowired
    private ApiKeyStore store;

    @Autowired
    private ControlTransactions transactions;

    @Autowired
    @Qualifier("controlJdbcClient") private JdbcClient control;

    @Autowired
    private CursorCodec cursors;

    @Test
    void createsAKeyOnceStoresOnlyItsHmacAndAuthenticatesIt() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller user = newUser(team);
        Instant expiry = clock.instant().plus(Duration.ofDays(2));
        HttpResponse<String> response = send(
                "POST",
                PATH,
                user.token(),
                json.writeValueAsString(Map.of("name", "ci-pipeline", "expiresAt", expiry.toString())));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode created = json.readTree(response.body());
        String id = created.path("keyId").asString();
        String key = created.path("key").asString();
        assertThat(id).matches("^[A-Za-z0-9]{16}$");
        assertThat(response.headers().firstValue("Location")).contains(PATH + "/" + id);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(created.path("name").asString()).isEqualTo("ci-pipeline");
        assertThat(Instant.parse(created.path("expiresAt").asString())).isEqualTo(expiry);
        assertThat(Instant.parse(created.path("createdAt").asString())).isEqualTo(clock.instant());
        assertThat(keys.parse(key).isPresent()).isTrue();
        byte[] stored = ApiTestFixtures.control()
                .sql("SELECT secret_hash FROM scheduler.api_keys WHERE key_id = :id")
                .param("id", id)
                .query(byte[].class)
                .single();
        assertThat(stored).hasSize(32);
        assertThat(java.util.Arrays.equals(stored, key.getBytes(StandardCharsets.UTF_8)))
                .isFalse();
        assertThat(keys.verify(keys.parse(key).orElseThrow(), stored, keys.currentPepperVersion())
                        .matches())
                .isTrue();
        assertThat(send("GET", "/v1/team", key, null).statusCode()).isEqualTo(200);

        JsonNode metadata = list(user).path("items").path(0);
        assertThat(metadata.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("keyId", "name", "createdBy", "expiresAt", "lastUsedAt", "revokedAt");
        assertThat(metadata.path("createdBy").asString())
                .isEqualTo(user.user().value().toString());
        assertThat(metadata.path("lastUsedAt").isNull()).isFalse();
        assertThat(metadata.toString().contains(key)).isFalse();
        assertThat(audits(team, "API_KEY_CREATED")).isEqualTo(1);
        String details = ApiTestFixtures.control()
                .sql("SELECT details::text FROM scheduler.audit_log WHERE team_id = :team")
                .param("team", team.value())
                .query(String.class)
                .single();
        assertThat(details).isEqualTo("{}");
    }

    @Test
    void expiryIsOptionalAndNamesMayHaveTheMaximumLength() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        JsonNode created = create(user, "a".repeat(64));
        assertThat(created.path("expiresAt").isNull()).isTrue();
        assertThat(list(user).path("items").path(0).path("expiresAt").isNull()).isTrue();
    }

    @Test
    void rejectsInvalidNamesDatesUnknownFieldsAndMalformedBodies() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        for (String body : List.of(
                "{}",
                "{\"name\":null}",
                "{\"name\":\"\"}",
                "{\"name\":\"   \"}",
                json.writeValueAsString(Map.of("name", "a".repeat(65))),
                "{\"name\":\"ci\",\"expiresAt\":\"invalid\"}",
                "{\"name\":\"ci\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}",
                json.writeValueAsString(
                        Map.of("name", "ci", "expiresAt", clock.instant().toString())),
                "{\"name\":\"ci\",\"teamId\":\"untrusted\"}",
                "{",
                "null")) {
            problem(
                    send("POST", PATH, user.token(), body),
                    400,
                    "validation",
                    body.equals("{") || body.equals("null") || body.contains("invalid")
                            ? "The request body is not valid JSON for this operation."
                            : "One or more fields are invalid.");
        }
        assertThat(list(user).path("items").size()).isZero();
    }

    @Test
    void everyManagementOperationRequiresAUserTokenAndATeam() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        String key = create(user, "automation").path("key").asString();
        for (String method : List.of("POST", "GET", "DELETE")) {
            String path = method.equals("DELETE") ? PATH + "/1234567890abcdef" : PATH;
            String body = method.equals("POST") ? "{\"name\":\"ci\"}" : null;
            problem(
                    send(method, path, key, body),
                    403,
                    "forbidden-for-api-key",
                    "This operation requires a user access token.");
            assertThat(send(method, path, null, body).statusCode()).isEqualTo(401);
            assertThat(send(method, path, newUser(null).token(), body).statusCode())
                    .isEqualTo(403);
        }
    }

    @Test
    void revocationIsIdempotentAndTakesEffectWithin60Seconds() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        JsonNode created = create(user, "ci");
        String id = created.path("keyId").asString();
        String key = created.path("key").asString();
        assertThat(send("GET", "/v1/team", key, null).statusCode()).isEqualTo(200);
        assertThat(send("DELETE", PATH + "/" + id, user.token(), null).statusCode())
                .isEqualTo(204);
        Instant revokedAt = timestamp(id, "revoked_at");
        clock.advance(Duration.ofSeconds(59));
        assertThat(send("GET", "/v1/team", key, null).statusCode()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(1));
        assertThat(send("GET", "/v1/team", key, null).statusCode()).isEqualTo(401);
        assertThat(send("DELETE", PATH + "/" + id, user.token(), null).statusCode())
                .isEqualTo(204);
        assertThat(timestamp(id, "revoked_at")).isEqualTo(revokedAt);
        assertThat(list(user).path("items").path(0).path("revokedAt").isNull()).isFalse();
        assertThat(audits(user.team(), "API_KEY_REVOKED")).isEqualTo(1);
    }

    @Test
    void anExpiredKeyIsRejectedEvenWhileItsAuthenticationRowIsCached() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        HttpResponse<String> response = send(
                "POST",
                PATH,
                user.token(),
                json.writeValueAsString(Map.of(
                        "name",
                        "temporary",
                        "expiresAt",
                        clock.instant().plusSeconds(10).toString())));
        assertThat(response.statusCode()).isEqualTo(201);
        String key = json.readTree(response.body()).path("key").asString();
        assertThat(send("GET", "/v1/team", key, null).statusCode()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(10));
        assertThat(send("GET", "/v1/team", key, null).statusCode()).isEqualTo(401);
    }

    @Test
    void listsAndRevocationsNeverExposeAnotherTeamsKeys() {
        Caller owner = newUser(ApiTestFixtures.insertTeam());
        Caller stranger = newUser(ApiTestFixtures.insertTeam());
        String id = create(owner, "private").path("keyId").asString();
        assertThat(list(stranger).path("items").size()).isZero();
        for (String keyId : List.of(id, "1234567890abcdef", "malformed", "x".repeat(100))) {
            problem(
                    send("DELETE", PATH + "/" + keyId, stranger.token(), null),
                    404,
                    "not-found",
                    "The API key does not exist.");
        }
        assertThat(timestamp(id, "revoked_at")).isNull();
    }

    @Test
    void cursorPaginationIsStableAndBoundToTheListAndTeam() {
        Caller owner = newUser(ApiTestFixtures.insertTeam());
        Caller stranger = newUser(ApiTestFixtures.insertTeam());
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            if (i == 3) {
                clock.advance(Duration.ofMillis(1));
            }
            expected.add(create(owner, "key-" + i).path("keyId").asString());
        }
        List<String> actual = new ArrayList<>();
        String cursor = null;
        String firstCursor = null;
        do {
            HttpResponse<String> response =
                    send("GET", PATH + "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor), owner.token(), null);
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode page = json.readTree(response.body());
            for (JsonNode item : page.path("items")) {
                actual.add(item.path("keyId").asString());
            }
            cursor = page.path("nextCursor").isNull()
                    ? null
                    : page.path("nextCursor").asString();
            if (firstCursor == null) {
                firstCursor = cursor;
            }
        } while (cursor != null);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected).doesNotHaveDuplicates();
        assertThat(actual.subList(0, 2)).containsExactlyInAnyOrderElementsOf(expected.subList(3, 5));
        assertThat(send("GET", PATH + "?cursor=" + firstCursor, stranger.token(), null)
                        .statusCode())
                .isEqualTo(400);
        String crossList = cursors.encode("team-members", owner.team(), List.of(expected.getFirst()));
        assertThat(send("GET", PATH + "?cursor=" + crossList, owner.token(), null)
                        .statusCode())
                .isEqualTo(400);
        for (List<String> invalid : List.of(
                List.of("bad-id"), List.of(expected.getFirst(), "extra"), List.of("bad-time", expected.getFirst()))) {
            String malformed = cursors.encode("api-keys", owner.team(), invalid);
            assertThat(send("GET", PATH + "?cursor=" + malformed, owner.token(), null)
                            .statusCode())
                    .isEqualTo(400);
        }
        for (String query : List.of("limit=0", "limit=201", "limit=bad", "cursor=bad", "cursor=")) {
            assertThat(send("GET", PATH + "?" + query, owner.token(), null).statusCode())
                    .isEqualTo(400);
        }
    }

    @Test
    void usageUpdatesAreCoalescedAndSqlEnforcesTheIntervalAcrossReplicas() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        JsonNode created = create(user, "ci");
        String id = created.path("keyId").asString();
        String key = created.path("key").asString();
        assertThat(timestamp(id, "last_used_at")).isNull();
        assertThat(send("GET", "/v1/team", key, null).statusCode()).isEqualTo(200);
        Instant first = timestamp(id, "last_used_at");
        clock.advance(Duration.ofSeconds(59));
        for (int i = 0; i < 3; i++) {
            assertThat(send("GET", "/v1/team", key, null).statusCode()).isEqualTo(200);
        }
        // A different process could bypass this process's cache; the database guard still applies.
        store.recordUse(user.team(), id, clock.instant());
        assertThat(timestamp(id, "last_used_at")).isEqualTo(first);
        clock.advance(Duration.ofSeconds(1));
        assertThat(send("GET", "/v1/team", key, null).statusCode()).isEqualTo(200);
        assertThat(timestamp(id, "last_used_at")).isEqualTo(first.plusSeconds(60));
        store.recordUse(ApiTestFixtures.insertTeam(), id, clock.instant().plusSeconds(60));
        assertThat(timestamp(id, "last_used_at")).isEqualTo(first.plusSeconds(60));
    }

    @Test
    void invalidCredentialsDoNotUpdateUsage() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        JsonNode created = create(user, "ci");
        String key = created.path("key").asString();
        String wrong = key.substring(0, key.length() - 1) + (key.endsWith("a") ? "b" : "a");
        assertThat(send("GET", "/v1/team", wrong, null).statusCode()).isEqualTo(401);
        assertThat(timestamp(created.path("keyId").asString(), "last_used_at")).isNull();
    }

    @Test
    void parallelRevocationsWriteOneAuditEntry() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        String id = create(user, "ci").path("keyId").asString();
        List<CompletableFuture<HttpResponse<String>>> requests = IntStream.range(0, 5)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> send("DELETE", PATH + "/" + id, user.token(), null)))
                .toList();
        for (CompletableFuture<HttpResponse<String>> request : requests) {
            assertThat(request.join().statusCode()).isEqualTo(204);
        }
        assertThat(audits(user.team(), "API_KEY_REVOKED")).isEqualTo(1);
    }

    @Test
    void auditFailuresRollBackBothCreationAndRevocation() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        IssuedApiKey issued = keys.issue();
        // Fail while producing the audit entry, after the key mutation has already executed.
        AuditWriter unavailableAudit = mock(AuditWriter.class);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(unavailableAudit)
                .record(any());
        ApiKeyStore failingAudit = new JdbcApiKeyStore(control, transactions, unavailableAudit);
        assertThatThrownBy(
                        () -> failingAudit.create(user.team(), user.user(), "rollback", issued, null, clock.instant()))
                .isInstanceOf(RuntimeException.class);
        assertThat(list(user).path("items").size()).isZero();
        String id = create(user, "ci").path("keyId").asString();
        assertThatThrownBy(() -> failingAudit.revoke(user.team(), user.user(), id, clock.instant()))
                .isInstanceOf(RuntimeException.class);
        assertThat(timestamp(id, "revoked_at")).isNull();
    }

    private JsonNode create(Caller user, String name) {
        HttpResponse<String> response = send("POST", PATH, user.token(), json.writeValueAsString(Map.of("name", name)));
        assertThat(response.statusCode()).as("API key creation status").isEqualTo(201);
        return json.readTree(response.body());
    }

    private JsonNode list(Caller user) {
        HttpResponse<String> response = send("GET", PATH, user.token(), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private static Instant timestamp(String id, String column) {
        assertThat(column).isIn("last_used_at", "revoked_at");
        OffsetDateTime value = ApiTestFixtures.control()
                .sql("SELECT " + column + " FROM scheduler.api_keys WHERE key_id = :id")
                .param("id", id)
                .query((rs, row) -> rs.getObject(1, OffsetDateTime.class))
                .list()
                .getFirst();
        return value == null ? null : value.toInstant();
    }

    private static long audits(TeamId team, String action) {
        return ApiTestFixtures.control()
                .sql("SELECT count(*) FROM scheduler.audit_log WHERE team_id = :team AND action = :action")
                .param("team", team.value())
                .param("action", action)
                .query(Long.class)
                .single();
    }

    private void problem(HttpResponse<String> response, int status, String code, String detail) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("type").asString()).isEqualTo("/errors/" + code);
        assertThat(body.path("detail").asString()).isEqualTo(detail);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {
        @Bean
        @Primary
        MutableClock apiKeyTestClock() {
            return MutableClock.at(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        }
    }
}
