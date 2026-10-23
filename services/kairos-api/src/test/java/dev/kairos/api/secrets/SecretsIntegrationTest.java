package dev.kairos.api.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.secrets.application.CredentialValue;
import dev.kairos.api.secrets.application.SecretSealer;
import dev.kairos.api.secrets.application.SecretStore;
import dev.kairos.api.secrets.infrastructure.JdbcSecretStore;
import dev.kairos.domain.id.TeamId;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.apikey.ApiKeyService;
import dev.kairos.security.cipher.SecretCipher;
import dev.kairos.security.keyset.KeyPurpose;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.testsupport.TestIds;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real credential APIs, encrypted row binding, concurrency, isolation, audit atomicity and redaction. */
@ExtendWith(OutputCaptureExtension.class)
class SecretsIntegrationTest extends ApiApplicationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private SecretStore store;

    @Autowired
    private SecretSealer sealer;

    @Autowired
    private KeysetService keys;

    @Autowired
    private SecretCipher cipher;

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private ControlTransactions transactions;

    @Autowired
    @Qualifier("controlJdbcClient") private JdbcClient control;

    @Test
    void createsAnEncryptedSecretAndNeverReturnsOrLogsItsValue(CapturedOutput output) {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller user = newUser(team);
        String canary = "canary-" + ApiTestFixtures.randomHex();
        HttpResponse<String> response = create(user, "orders-api-token", "BEARER", Map.of("token", canary));
        assertThat(response.statusCode()).as("secret create status").isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).contains("/v1/secrets/orders-api-token");
        assertThat(response.headers().firstValue("ETag")).contains("\"1\"");
        JsonNode body = json.readTree(response.body());
        assertThat(body.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("name", "kind", "version", "createdAt");
        assertThat(body.path("version").asInt()).isEqualTo(1);
        byte[] encrypted = ciphertext(team, "orders-api-token", 1);
        assertThat(new String(encrypted, StandardCharsets.ISO_8859_1).contains(canary))
                .isFalse();
        var aead = keys.aead(team, KeyPurpose.SECRETS, store.keyset(team).orElseThrow());
        byte[] plaintext = cipher.decryptSecret(aead, "orders-api-token", 1, encrypted);
        assertThat(json.readTree(plaintext).path("token").asString().equals(canary))
                .isTrue();
        assertThatThrownBy(() -> cipher.decryptSecret(aead, "other-name", 1, encrypted))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> cipher.decryptSecret(aead, "orders-api-token", 2, encrypted))
                .isInstanceOf(RuntimeException.class);
        TeamId other = ApiTestFixtures.insertTeam();
        var otherAead = keys.aead(other, KeyPurpose.SECRETS, keys.createTeamKeyset(other, KeyPurpose.SECRETS));
        assertThatThrownBy(() -> cipher.decryptSecret(otherAead, "orders-api-token", 1, encrypted))
                .isInstanceOf(RuntimeException.class);
        assertThat(list(user).toString().contains(canary)).isFalse();
        String audit = control.sql("SELECT details::text FROM scheduler.audit_log WHERE team_id = :team")
                .param("team", team.value())
                .query(String.class)
                .single();
        assertThat(audit.contains(canary)).isFalse();
        assertThat(output.getAll().contains(canary)).isFalse();
        assertThat(new CredentialValue(null, null, canary, null, null, null)
                        .toString()
                        .contains(canary))
                .isFalse();
    }

    @Test
    void supportsAllFourKindsAndRejectsDuplicateNames() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        Map<String, Map<String, String>> values = Map.of(
                "BASIC",
                Map.of("username", "service", "password", ApiTestFixtures.randomHex()),
                "BEARER",
                Map.of("token", ApiTestFixtures.randomHex()),
                "API_KEY",
                Map.of("key", ApiTestFixtures.randomHex()),
                "OAUTH2_CLIENT",
                Map.of("clientId", "client", "clientSecret", ApiTestFixtures.randomHex()));
        values.forEach((kind, value) -> {
            String name = kind.toLowerCase(java.util.Locale.ROOT);
            assertThat(create(user, name, kind, value).statusCode()).isEqualTo(201);
            HttpResponse<String> duplicate = create(user, name, kind, value);
            problem(
                    duplicate,
                    409,
                    "secret-exists",
                    "A secret with this name already exists. Use PUT to add a version.");
        });
        assertThat(list(user).path("items").size()).isEqualTo(4);
    }

    @Test
    void rotationAddsVersionsWithoutIfMatchAndPreservesCreationTimeAndKind() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        JsonNode first = json.readTree(create(user, "orders", "BEARER", Map.of("token", ApiTestFixtures.randomHex()))
                .body());
        String next = ApiTestFixtures.randomHex();
        HttpResponse<String> updated = send(
                "PUT",
                "/v1/secrets/orders",
                user.token(),
                json.writeValueAsString(Map.of("value", Map.of("token", next))));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.headers().firstValue("ETag")).contains("\"2\"");
        JsonNode body = json.readTree(updated.body());
        assertThat(Instant.parse(body.path("createdAt").asString()).truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .isEqualTo(Instant.parse(first.path("createdAt").asString())
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThat(list(user).path("items").path(0).path("version").asInt()).isEqualTo(2);
        var aead = keys.aead(
                user.team(), KeyPurpose.SECRETS, store.keyset(user.team()).orElseThrow());
        assertThat(json.readTree(cipher.decryptSecret(aead, "orders", 2, ciphertext(user.team(), "orders", 2)))
                        .path("token")
                        .asString()
                        .equals(next))
                .isTrue();
        assertThat(control.sql("SELECT count(*) FROM scheduler.team_secrets WHERE team_id = :team")
                        .param("team", user.team().value())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void missingAndWrongShapeValuesUseTheLldErrorsWithoutEchoingInput() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        for (Map<String, String> value : List.of(
                Map.<String, String>of(),
                Map.of("key", "not-a-bearer"),
                Map.of("token", ""),
                Map.of("token", "has space"),
                Map.of("token", "line\r\nbreak"),
                Map.of("token", "x".repeat(8193)),
                Map.of("token", "ok", "password", "extra"))) {
            problem(create(user, "invalid", "BEARER", value), 400, "validation", CredentialValue.SHAPE_DETAIL);
        }
        assertThat(create(user, "bad:username", "BASIC", Map.of("username", "user", "password", "test"))
                        .statusCode())
                .isEqualTo(400);
        assertThat(create(user, "basic", "BASIC", Map.of("username", "user:name", "password", "test"))
                        .statusCode())
                .isEqualTo(400);
        assertThat(create(
                                user,
                                "too-large",
                                "BASIC",
                                Map.of("username", "x".repeat(8192), "password", "y".repeat(8192)))
                        .statusCode())
                .isEqualTo(400);
        for (String raw : List.of(
                "{}",
                "{\"name\":\"missing-kind\"}",
                "{\"name\":\"x\",\"kind\":\"UNKNOWN\"}",
                "{\"name\":\"x\",\"kind\":\"BEARER\"}",
                "{\"name\":\"x\",\"kind\":\"BEARER\",\"value\":{\"token\":123}}",
                "{\"name\":\"x\",\"kind\":\"BEARER\",\"value\":{\"token\":true}}",
                "{\"name\":\"x\",\"kind\":\"BEARER\",\"value\":{\"token\":null}}",
                "{\"name\":\"x\",\"kind\":\"BEARER\",\"value\":{\"unexpected\":\"canary\"}}",
                "{\"name\":\"x\",\"kind\":\"BEARER\",\"value\":{\"token\":\"canary\"},\"extra\":1}")) {
            assertThat(send("POST", "/v1/secrets", user.token(), raw).statusCode())
                    .isEqualTo(400);
        }
        create(user, "existing", "BEARER", Map.of("token", "valid"));
        problem(
                send("PUT", "/v1/secrets/existing", user.token(), "{\"value\":{}}"),
                400,
                "validation",
                "One or more fields are invalid.");
    }

    @Test
    void crossTeamReadsUpdatesAndDeletesAreIsolatedAndApiKeysMayManageSecrets() {
        Caller owner = newUser(ApiTestFixtures.insertTeam());
        Caller stranger = newUser(ApiTestFixtures.insertTeam());
        assertThat(create(owner, "private", "BEARER", Map.of("token", ApiTestFixtures.randomHex()))
                        .statusCode())
                .isEqualTo(201);
        assertThat(list(stranger).path("items").isEmpty()).isTrue();
        problem(
                send("PUT", "/v1/secrets/private", stranger.token(), "{\"value\":{\"token\":\"test\"}}"),
                404,
                "not-found",
                "The secret does not exist.");
        problem(
                send("DELETE", "/v1/secrets/private", stranger.token(), null),
                404,
                "not-found",
                "The secret does not exist.");
        var key = apiKeys.issue();
        ApiTestFixtures.insertApiKey(key.keyId(), owner.team(), owner.user(), key.secretHash(), key.pepperVersion());
        assertThat(send("GET", "/v1/secrets", key.key().reveal(), null).statusCode())
                .isEqualTo(200);
        assertThat(send("PUT", "/v1/secrets/private", key.key().reveal(), "{\"value\":{\"token\":\"rotated\"}}")
                        .statusCode())
                .isEqualTo(200);
        assertThat(send("DELETE", "/v1/secrets/private", key.key().reveal(), null)
                        .statusCode())
                .isEqualTo(204);
        assertThat(list(owner).path("items").isEmpty()).isTrue();
        problem(
                send("DELETE", "/v1/secrets/private", owner.token(), null),
                404,
                "not-found",
                "The secret does not exist.");
        assertThat(send("GET", "/v1/secrets", null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/v1/secrets", newUser(null).token(), null).statusCode())
                .isEqualTo(403);
    }

    @Test
    void deletionRefusesCurrentEndpointReferencesButIgnoresOtherTeams() {
        Caller owner = newUser(ApiTestFixtures.insertTeam());
        create(owner, "orders-token", "BEARER", Map.of("token", ApiTestFixtures.randomHex()));
        endpoint(owner.team(), "orders-create", "orders-token");
        problem(
                send("DELETE", "/v1/secrets/orders-token", owner.token(), null),
                409,
                "secret-in-use",
                "The secret is used by endpoint orders-create.");
        assertThat(store.find(owner.team(), "orders-token")).isPresent();
        control.sql("DELETE FROM scheduler.api_endpoints WHERE team_id = :team")
                .param("team", owner.team().value())
                .update();
        endpoint(ApiTestFixtures.insertTeam(), "foreign-orders", "orders-token");
        assertThat(send("DELETE", "/v1/secrets/orders-token", owner.token(), null)
                        .statusCode())
                .isEqualTo(204);
    }

    @Test
    void retainedEndpointVersionsAlsoPreventSecretDeletion() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        create(user, "old-token", "BEARER", Map.of("token", ApiTestFixtures.randomHex()));
        endpoint(user.team(), "versioned-endpoint", "old-token");
        control.sql("""
                INSERT INTO scheduler.api_endpoint_versions (endpoint_id, version, spec, created_at, created_by)
                SELECT endpoint_id, version, spec, created_at, 'system:test'
                  FROM scheduler.api_endpoints WHERE team_id = :team
                """).param("team", user.team().value()).update();
        control.sql("UPDATE scheduler.api_endpoints SET version = 2, spec = '{}'::jsonb WHERE team_id = :team")
                .param("team", user.team().value())
                .update();
        problem(
                send("DELETE", "/v1/secrets/old-token", user.token(), null),
                409,
                "secret-in-use",
                "The secret is used by endpoint versioned-endpoint.");
        assertThat(store.find(user.team(), "old-token")).isPresent();
    }

    @Test
    void paginationIsTeamAndListBoundAndNeverLoadsCiphertext() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        for (String name : List.of("alpha", "bravo", "charlie"))
            create(user, name, "BEARER", Map.of("token", ApiTestFixtures.randomHex()));
        JsonNode first = json.readTree(
                send("GET", "/v1/secrets?limit=1", user.token(), null).body());
        String cursor = first.path("nextCursor").asString();
        assertThat(first.path("items").path(0).path("name").asString()).isEqualTo("alpha");
        JsonNode second = json.readTree(send("GET", "/v1/secrets?limit=1&cursor=" + cursor, user.token(), null)
                .body());
        assertThat(second.path("items").path(0).path("name").asString()).isEqualTo("bravo");
        JsonNode last = json.readTree(send(
                        "GET",
                        "/v1/secrets?limit=1&cursor="
                                + second.path("nextCursor").asString(),
                        user.token(),
                        null)
                .body());
        assertThat(last.path("nextCursor").isNull()).isTrue();
        assertThat(send(
                                "GET",
                                "/v1/secrets?cursor=" + cursor,
                                newUser(ApiTestFixtures.insertTeam()).token(),
                                null)
                        .statusCode())
                .isEqualTo(400);
        assertThat(send("GET", "/v1/ca-bundles?cursor=" + cursor, user.token(), null)
                        .statusCode())
                .isEqualTo(400);
        for (String query : List.of("cursor=bad", "limit=0", "limit=201", "limit=bad")) {
            assertThat(send("GET", "/v1/secrets?" + query, user.token(), null).statusCode())
                    .isEqualTo(400);
        }
    }

    @Test
    void concurrentCreationInitializesOneCanonicalKeysetAndRotationNeverLosesAVersion() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        List<CompletableFuture<HttpResponse<String>>> creates = IntStream.range(0, 4)
                .mapToObj(i -> CompletableFuture.supplyAsync(
                        () -> create(user, "simultaneous", "BEARER", Map.of("token", "initial"))))
                .toList();
        List<Integer> statuses = creates.stream()
                .map(CompletableFuture::join)
                .map(HttpResponse::statusCode)
                .toList();
        assertThat(statuses).containsExactlyInAnyOrder(201, 409, 409, 409);
        List<CompletableFuture<HttpResponse<String>>> rotations = IntStream.range(0, 4)
                .mapToObj(i -> CompletableFuture.supplyAsync(() ->
                        send("PUT", "/v1/secrets/simultaneous", user.token(), "{\"value\":{\"token\":\"rotated\"}}")))
                .toList();
        List<Integer> versions = new ArrayList<>();
        for (var future : rotations) {
            var response = future.join();
            assertThat(response.statusCode()).isEqualTo(200);
            versions.add(json.readTree(response.body()).path("version").asInt());
        }
        assertThat(versions).containsExactlyInAnyOrder(2, 3, 4, 5);
        var aead = keys.aead(
                user.team(), KeyPurpose.SECRETS, store.keyset(user.team()).orElseThrow());
        for (int version = 1; version <= 5; version++) {
            assertThat(json.readTree(cipher.decryptSecret(
                                    aead, "simultaneous", version, ciphertext(user.team(), "simultaneous", version)))
                            .path("token")
                            .isString())
                    .isTrue();
        }
    }

    @Test
    void failedAuditRollsBackCreateRotateAndDelete() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        AuditWriter broken = mock(AuditWriter.class);
        doThrow(new IllegalStateException("audit unavailable")).when(broken).record(any());
        SecretStore failing = new JdbcSecretStore(control, transactions, broken);
        byte[] encrypted =
                sealer.seal(user.team(), "rollback", 1, new CredentialValue(null, null, "initial", null, null, null));
        assertThatThrownBy(() -> failing.create(
                        user.team(), "system:test", "rollback", CredentialValue.Kind.BEARER, encrypted, Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.find(user.team(), "rollback")).isEmpty();
        create(user, "rollback", "BEARER", Map.of("token", "initial"));
        var before = store.find(user.team(), "rollback").orElseThrow();
        assertThatThrownBy(() -> failing.rotate(user.team(), "system:test", before, encrypted, Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.find(user.team(), "rollback").orElseThrow().version()).isEqualTo(1);
        assertThatThrownBy(() -> failing.delete(user.team(), "system:test", "rollback"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.find(user.team(), "rollback")).isPresent();
    }

    private HttpResponse<String> create(Caller user, String name, String kind, Map<String, String> value) {
        return send(
                "POST",
                "/v1/secrets",
                user.token(),
                json.writeValueAsString(Map.of("name", name, "kind", kind, "value", value)));
    }

    private JsonNode list(Caller user) {
        var response = send("GET", "/v1/secrets", user.token(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private byte[] ciphertext(TeamId team, String name, int version) {
        return control.sql(
                        "SELECT ciphertext FROM scheduler.team_secrets WHERE team_id = :team AND name = :name AND version = :version")
                .param("team", team.value())
                .param("name", name)
                .param("version", version)
                .query(byte[].class)
                .single();
    }

    private void endpoint(TeamId team, String name, String secret) {
        control.sql("""
                INSERT INTO scheduler.api_endpoints (endpoint_id, team_id, name, version, spec, created_at, updated_at)
                VALUES (:id, :team, :name, 1, CAST(:spec AS jsonb), :now, :now)
                """)
                .param("id", TestIds.endpointId().value())
                .param("team", team.value())
                .param("name", name)
                .param("spec", json.writeValueAsString(Map.of("auth", Map.of("secretRef", secret))))
                .param("now", ApiTestFixtures.utc(Instant.now()))
                .update();
    }

    private void problem(HttpResponse<String> response, int status, String code, String detail) {
        assertThat(response.statusCode()).as("problem status").isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("type").asString()).isEqualTo("/errors/" + code);
        assertThat(body.path("detail").asString()).isEqualTo(detail);
    }
}
