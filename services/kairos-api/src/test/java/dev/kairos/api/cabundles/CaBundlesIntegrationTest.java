package dev.kairos.api.cabundles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.cabundles.application.CaBundleStore;
import dev.kairos.api.cabundles.application.CaCertificates;
import dev.kairos.api.cabundles.infrastructure.JdbcCaBundleStore;
import dev.kairos.api.support.paging.CursorCodec;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.apikey.ApiKeyService;
import dev.kairos.testsupport.TestIds;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real CA bundle APIs: parsing, reference checks, team isolation, pagination and audit rollback. */
class CaBundlesIntegrationTest extends ApiApplicationTest {
    private static final Instant NOW = Instant.now();
    private static final String VALID =
            TestCertificates.pem(true, true, NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(365)));
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private CaBundleStore store;

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private CursorCodec cursors;

    @Autowired
    private ControlTransactions transactions;

    @Autowired
    @Qualifier("controlJdbcClient") private JdbcClient control;

    @Test
    void storesPublicCertificatesAndReturnsOnlyMetadata() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        HttpResponse<String> response = create(user, "corp-internal-ca", VALID);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).contains("/v1/ca-bundles/corp-internal-ca");
        assertThat(json.readTree(response.body()).properties().stream()
                        .map(Map.Entry::getKey)
                        .toList())
                .containsExactlyInAnyOrder("name", "createdAt");
        JsonNode page = get(user, "");
        assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("items").path(0).has("pem")).isFalse();
        assertThat(page.path("nextCursor").isNull()).isTrue();
        assertThat(control.sql(
                                "SELECT pem FROM scheduler.team_ca_bundles WHERE team_id = :team AND name = 'corp-internal-ca'")
                        .param("team", user.team().value())
                        .query(String.class)
                        .single())
                .isEqualTo(VALID);
        assertThat(control.sql(
                                "SELECT details::text FROM scheduler.audit_log WHERE team_id = :team AND action = 'CA_BUNDLE_CREATED'")
                        .param("team", user.team().value())
                        .query(String.class)
                        .single())
                .isEqualTo("{}");
        problem(
                create(user, "corp-internal-ca", VALID),
                409,
                "ca-bundle-exists",
                "A CA bundle with this name already exists.");
    }

    @Test
    void validatesCaFlagsValidityCertificateCountSizeAndStrictPemContents() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        List<String> invalid = List.of(
                "",
                "not pem",
                " ",
                VALID + "trailing content",
                "unrelated prefix" + VALID,
                VALID.replace("CERTIFICATE", "PRIVATE KEY"),
                VALID.replace("CERTIFICATE", "PUBLIC KEY"),
                VALID.repeat(11),
                VALID + " ".repeat(65536),
                "é" + VALID,
                TestCertificates.pem(false, false, NOW.minusSeconds(60), NOW.plusSeconds(3600)),
                TestCertificates.pem(true, false, NOW.minusSeconds(60), NOW.plusSeconds(3600)),
                TestCertificates.pem(true, true, NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1))),
                TestCertificates.pem(true, true, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(2))));
        for (String pem : invalid) {
            problem(create(user, "invalid-ca", pem), 400, "validation", CaCertificates.INVALID);
        }
        problem(
                send("POST", "/v1/ca-bundles", user.token(), "{\"name\":\"missing-pem\"}"),
                400,
                "validation",
                CaCertificates.INVALID);
        assertThat(create(user, "ten-cas", VALID.repeat(10)).statusCode()).isEqualTo(201);
        assertThat(create(user, "bad/name", VALID).statusCode()).isEqualTo(400);
    }

    @Test
    void isolatesTeamsAndAllowsApiKeysForAllBundleOperations() {
        Caller owner = newUser(ApiTestFixtures.insertTeam());
        Caller stranger = newUser(ApiTestFixtures.insertTeam());
        create(owner, "private-ca", VALID);
        assertThat(get(stranger, "").path("items").isEmpty()).isTrue();
        problem(
                send("DELETE", "/v1/ca-bundles/private-ca", stranger.token(), null),
                404,
                "not-found",
                "The CA bundle does not exist.");
        assertThat(create(stranger, "private-ca", VALID).statusCode()).isEqualTo(201);
        var key = apiKeys.issue();
        ApiTestFixtures.insertApiKey(key.keyId(), owner.team(), owner.user(), key.secretHash(), key.pepperVersion());
        assertThat(send(
                                "POST",
                                "/v1/ca-bundles",
                                key.key().reveal(),
                                json.writeValueAsString(Map.of("name", "automation-ca", "pem", VALID)))
                        .statusCode())
                .isEqualTo(201);
        assertThat(send("GET", "/v1/ca-bundles", key.key().reveal(), null).statusCode())
                .isEqualTo(200);
        assertThat(send("DELETE", "/v1/ca-bundles/private-ca", key.key().reveal(), null)
                        .statusCode())
                .isEqualTo(204);
        assertThat(store.find(stranger.team(), "private-ca")).isPresent();
        assertThat(send("GET", "/v1/ca-bundles", null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/v1/ca-bundles", newUser(null).token(), null).statusCode())
                .isEqualTo(403);
    }

    @Test
    void deletionIsBlockedByAnEndpointAndForeignReferencesDoNotBlockIt() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        create(user, "corp-ca", VALID);
        var id = TestIds.endpointId();
        control.sql("""
                INSERT INTO scheduler.api_endpoints (endpoint_id, team_id, name, version, spec, created_at, updated_at)
                VALUES (:id, :team, 'orders-create', 1, '{"caBundle":"corp-ca"}', :now, :now)
                """)
                .param("id", id.value())
                .param("team", user.team().value())
                .param("now", ApiTestFixtures.utc(Instant.now()))
                .update();
        problem(
                send("DELETE", "/v1/ca-bundles/corp-ca", user.token(), null),
                409,
                "ca-bundle-in-use",
                "The CA bundle is used by endpoint orders-create.");
        assertThat(store.find(user.team(), "corp-ca")).isPresent();
        control.sql("UPDATE scheduler.api_endpoints SET team_id = :team WHERE endpoint_id = :id")
                .param("team", ApiTestFixtures.insertTeam().value())
                .param("id", id.value())
                .update();
        assertThat(send("DELETE", "/v1/ca-bundles/corp-ca", user.token(), null).statusCode())
                .isEqualTo(204);
        problem(
                send("DELETE", "/v1/ca-bundles/corp-ca", user.token(), null),
                404,
                "not-found",
                "The CA bundle does not exist.");
    }

    @Test
    void retainedEndpointVersionsAlsoPreventBundleDeletion() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        create(user, "old-ca", VALID);
        control.sql("""
                INSERT INTO scheduler.api_endpoints (endpoint_id, team_id, name, version, spec, created_at, updated_at)
                VALUES (:id, :team, 'versioned-endpoint', 2, '{}'::jsonb, :now, :now)
                """)
                .param("id", TestIds.endpointId().value())
                .param("team", user.team().value())
                .param("now", ApiTestFixtures.utc(Instant.now()))
                .update();
        control.sql("""
                INSERT INTO scheduler.api_endpoint_versions (endpoint_id, version, spec, created_at, created_by)
                SELECT endpoint_id, 1, '{"caBundle":"old-ca"}'::jsonb, created_at, 'system:test'
                  FROM scheduler.api_endpoints WHERE team_id = :team
                """).param("team", user.team().value()).update();
        problem(
                send("DELETE", "/v1/ca-bundles/old-ca", user.token(), null),
                409,
                "ca-bundle-in-use",
                "The CA bundle is used by endpoint versioned-endpoint.");
        assertThat(store.find(user.team(), "old-ca")).isPresent();
    }

    @Test
    void paginationUsesSignedTeamBoundCursors() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        for (String name : List.of("alpha", "bravo", "charlie")) create(user, name, VALID);
        JsonNode first = get(user, "?limit=2");
        assertThat(first.path("items").size()).isEqualTo(2);
        String cursor = first.path("nextCursor").asString();
        JsonNode last = get(user, "?limit=2&cursor=" + cursor);
        assertThat(last.path("items").path(0).path("name").asString()).isEqualTo("charlie");
        assertThat(last.path("nextCursor").isNull()).isTrue();
        for (String query : List.of(
                "cursor=bad",
                "limit=0",
                "limit=201",
                "limit=bad",
                "cursor=" + cursors.encode("ca-bundles", user.team(), List.of("bad/name")),
                "cursor=" + cursors.encode("ca-bundles", user.team(), List.of("alpha", "extra")))) {
            assertThat(send("GET", "/v1/ca-bundles?" + query, user.token(), null)
                            .statusCode())
                    .isEqualTo(400);
        }
        assertThat(send(
                                "GET",
                                "/v1/ca-bundles?cursor=" + cursor,
                                newUser(ApiTestFixtures.insertTeam()).token(),
                                null)
                        .statusCode())
                .isEqualTo(400);
        assertThat(send("GET", "/v1/secrets?cursor=" + cursor, user.token(), null)
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void concurrentCreatesYieldOneBundleAndFailedAuditsRollBackMutations() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        var a = CompletableFuture.supplyAsync(() -> create(user, "concurrent-ca", VALID));
        var b = CompletableFuture.supplyAsync(() -> create(user, "concurrent-ca", VALID));
        assertThat(List.of(a.join().statusCode(), b.join().statusCode())).containsExactlyInAnyOrder(201, 409);
        AuditWriter broken = mock(AuditWriter.class);
        doThrow(new IllegalStateException("audit unavailable")).when(broken).record(any());
        CaBundleStore failing = new JdbcCaBundleStore(control, transactions, broken);
        assertThatThrownBy(() -> failing.create(user.team(), "system:test", "rollback-ca", VALID, Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.find(user.team(), "rollback-ca")).isEmpty();
        assertThatThrownBy(() -> failing.delete(user.team(), "system:test", "concurrent-ca"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.find(user.team(), "concurrent-ca")).isPresent();
    }

    private HttpResponse<String> create(Caller user, String name, String pem) {
        return send("POST", "/v1/ca-bundles", user.token(), json.writeValueAsString(Map.of("name", name, "pem", pem)));
    }

    private JsonNode get(Caller user, String query) {
        var response = send("GET", "/v1/ca-bundles" + query, user.token(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private void problem(HttpResponse<String> response, int status, String code, String detail) {
        assertThat(response.statusCode()).as("problem status").isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("type").asString()).isEqualTo("/errors/" + code);
        assertThat(body.path("detail").asString()).isEqualTo(detail);
    }
}
