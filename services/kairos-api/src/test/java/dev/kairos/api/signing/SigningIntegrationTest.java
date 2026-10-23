package dev.kairos.api.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.signing.application.SigningStore;
import dev.kairos.api.signing.application.SigningUseCase;
import dev.kairos.api.signing.infrastructure.JdbcSigningStore;
import dev.kairos.domain.id.TeamId;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.apikey.ApiKeyService;
import dev.kairos.security.apikey.IssuedApiKey;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.NewSigningKey;
import dev.kairos.security.signing.RequestSignatures;
import dev.kairos.security.signing.RequestSigner;
import dev.kairos.security.signing.SignatureHeaders;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real rotation, cryptographic overlap, concurrent updates, expiry and transaction rollback. */
@Import(SigningIntegrationTest.TestClock.class)
@TestPropertySource(properties = "kairos.api.signing.prune-interval=1h")
@ExtendWith(OutputCaptureExtension.class)
class SigningIntegrationTest extends ApiApplicationTest {
    private static final String PATH = "/v1/team/signing-key:rotate";
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private KeysetService keysets;

    @Autowired
    private SigningStore store;

    @Autowired
    private SigningUseCase signing;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private ControlTransactions transactions;

    @Autowired
    @Qualifier("controlJdbcClient") private JdbcClient control;

    @Test
    void rotationImmediatelySignsWithTheNewKeyAndKeepsThePreviousKeyDuringOverlap(CapturedOutput output)
            throws Exception {
        Seed seed = seed();
        Caller user = newUser(seed.team());
        HttpResponse<String> response = send("POST", PATH, user.token(), null);
        assertThat(response.statusCode()).as("rotation status").isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode rotated = json.readTree(response.body());
        String exported = rotated.path("signingKey").asString();
        assertThat(rotated.path("keyVersion").asInt()).isEqualTo(2);
        Instant until = Instant.parse(rotated.path("previousKeyValidUntil").asString());
        assertThat(until).isEqualTo(clock.instant().plus(Duration.ofDays(1)));
        SigningStore.Stored stored = store.find(seed.team()).orElseThrow();
        assertThat(stored.previousUntil()).isEqualTo(until);
        assertThat(stored.sealed().equals(seed.key().sealed())).isFalse();
        var current = keysets.signingKey(seed.team(), stored.sealed());
        assertThat(current.version()).isEqualTo(2);
        var run = TestIds.runId(7);
        SignatureHeaders headers = new RequestSigner(clock).sign(current, "POST", "/orders", run, new byte[0]);
        assertThat(headers.signature()).startsWith("v2=");
        assertThat(RequestSignatures.of(List.of(exported), clock, Duration.ofMinutes(5))
                        .verify(
                                "POST",
                                "/orders",
                                run.toString(),
                                new byte[0],
                                headers.timestamp(),
                                headers.signature()))
                .isTrue();
        byte[] previousTag =
                keysets.signingKey(seed.team(), seed.key().sealed()).mac().computeMac(new byte[] {1});
        current.mac().verifyMac(previousTag, new byte[] {1});
        assertThat(auditCount(seed.team(), "SIGNING_KEY_ROTATED")).isEqualTo(1);
        String auditDetails = control.sql("SELECT details::text FROM scheduler.audit_log WHERE team_id = :team")
                .param("team", seed.team().value())
                .query(String.class)
                .single();
        assertThat(auditDetails.contains(exported)).isFalse();
        assertThat(output.getAll().contains(exported)).isFalse();
        assertThat(new SigningUseCase.Rotated(dev.kairos.security.SecretValue.of(exported), 2, until)
                        .toString()
                        .contains(exported))
                .isFalse();
        assertThat(new dev.kairos.api.signing.web.SigningController.RotatedResponse(exported, 2, until)
                        .toString()
                        .contains(exported))
                .isFalse();
    }

    @Test
    void onlyAUserWithATeamMayRotate() {
        Seed seed = seed();
        Caller user = newUser(seed.team());
        IssuedApiKey key = apiKeys.issue();
        ApiTestFixtures.insertApiKey(key.keyId(), seed.team(), user.user(), key.secretHash(), key.pepperVersion());
        HttpResponse<String> response = send("POST", PATH, key.key().reveal(), null);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(json.readTree(response.body()).path("type").asString()).isEqualTo("/errors/forbidden-for-api-key");
        assertThat(send("POST", PATH, null, null).statusCode()).isEqualTo(401);
        assertThat(send("POST", PATH, newUser(null).token(), null).statusCode()).isEqualTo(403);
        assertThat(keysets.signingKey(
                                seed.team(),
                                store.find(seed.team()).orElseThrow().sealed())
                        .version())
                .isEqualTo(1);
    }

    @Test
    void rotationUsesOnlyTheCallersTeamAndMissingKeysetsFailClosed() {
        Seed first = seed();
        Seed second = seed();
        assertThat(send("POST", PATH, newUser(first.team()).token(), null).statusCode())
                .isEqualTo(200);
        assertThat(store.find(second.team()).orElseThrow().sealed())
                .isEqualTo(second.key().sealed());
        HttpResponse<String> missing =
                send("POST", PATH, newUser(ApiTestFixtures.insertTeam()).token(), null);
        assertThat(missing.statusCode()).isEqualTo(503);
        assertThat(missing.headers().firstValue("Retry-After")).contains("1");
    }

    @Test
    void simultaneousRotationsProduceDistinctVersionsWithoutLostUpdates() {
        Seed seed = seed();
        Caller user = newUser(seed.team());
        List<CompletableFuture<HttpResponse<String>>> responses = IntStream.range(0, 5)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> send("POST", PATH, user.token(), null)))
                .toList();
        List<Integer> versions = responses.stream()
                .map(CompletableFuture::join)
                .map(response -> {
                    assertThat(response.statusCode())
                            .as("concurrent rotation status")
                            .isEqualTo(200);
                    return json.readTree(response.body()).path("keyVersion").asInt();
                })
                .toList();
        assertThat(versions).containsExactlyInAnyOrder(2, 3, 4, 5, 6);
        assertThat(keysets.signingKey(
                                seed.team(),
                                store.find(seed.team()).orElseThrow().sealed())
                        .version())
                .isEqualTo(6);
        assertThat(auditCount(seed.team(), "SIGNING_KEY_ROTATED")).isEqualTo(5);
    }

    @Test
    void expiredKeysArePrunedUsingThePersistedDeadlineEvenAfterConfigurationChanges() throws Exception {
        Seed seed = seed();
        SigningUseCase shortOverlap = new SigningUseCase(store, keysets, clock, Duration.ofSeconds(2));
        shortOverlap.rotate(seed.team(), "system:test");
        SigningStore.Stored rotated = store.find(seed.team()).orElseThrow();
        byte[] previous =
                keysets.signingKey(seed.team(), seed.key().sealed()).mac().computeMac(new byte[] {7});
        assertThat(shortOverlap.pruneExpired(100)).isZero();
        clock.advance(Duration.ofSeconds(2));
        SigningUseCase restarted = new SigningUseCase(store, keysets, clock, Duration.ofDays(7));
        assertThat(restarted.pruneExpired(100)).isGreaterThanOrEqualTo(1);
        SigningStore.Stored pruned = store.find(seed.team()).orElseThrow();
        assertThat(pruned.previousUntil()).isNull();
        assertThat(pruned.sealed().equals(rotated.sealed())).isFalse();
        assertThat(keysets.signingKey(seed.team(), pruned.sealed()).version()).isEqualTo(2);
        assertThatThrownBy(() ->
                        keysets.signingKey(seed.team(), pruned.sealed()).mac().verifyMac(previous, new byte[] {7}))
                .isInstanceOf(java.security.GeneralSecurityException.class);
        assertThat(auditCount(seed.team(), "SIGNING_KEY_PRUNED")).isEqualTo(1);
        restarted.pruneExpired(100);
        assertThat(auditCount(seed.team(), "SIGNING_KEY_PRUNED")).isEqualTo(1);
    }

    @Test
    void staleCleanupCannotOverwriteANewerRotationOrWriteAnAuditEntry() {
        Seed seed = seed();
        signing.rotate(seed.team(), "system:test");
        SigningStore.Stored stale = store.find(seed.team()).orElseThrow();
        signing.rotate(seed.team(), "system:test");
        assertThat(store.replace(
                        stale,
                        keysets.pruneSigningKeys(seed.team(), stale.sealed()),
                        null,
                        clock.instant(),
                        new AuditWriter.Entry(seed.team(), "system:test", "STALE_CLEANUP", "team", null, Map.of())))
                .isFalse();
        assertThat(keysets.signingKey(
                                seed.team(),
                                store.find(seed.team()).orElseThrow().sealed())
                        .version())
                .isEqualTo(3);
        assertThat(auditCount(seed.team(), "STALE_CLEANUP")).isZero();
    }

    @Test
    void auditFailureRollsBackTheKeysetAndItsDeadline() {
        Seed seed = seed();
        AuditWriter broken = mock(AuditWriter.class);
        doThrow(new IllegalStateException("audit unavailable")).when(broken).record(any());
        SigningStore failing = new JdbcSigningStore(control, transactions, broken);
        SigningStore.Stored before = store.find(seed.team()).orElseThrow();
        NewSigningKey rotated = keysets.rotateSigningKey(seed.team(), before.sealed());
        assertThatThrownBy(() -> failing.replace(
                        before,
                        rotated.sealed(),
                        clock.instant().plusSeconds(2),
                        clock.instant(),
                        new AuditWriter.Entry(
                                seed.team(), "system:test", "SIGNING_KEY_ROTATED", "team", null, Map.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.find(seed.team()).orElseThrow()).isEqualTo(before);
    }

    @Test
    void repeatedOptimisticConflictsFailWithABoundedRetry() {
        Seed seed = seed();
        SigningStore conflicts = mock(SigningStore.class);
        when(conflicts.find(seed.team()))
                .thenReturn(Optional.of(store.find(seed.team()).orElseThrow()));
        SigningUseCase useCase = new SigningUseCase(conflicts, keysets, clock, Duration.ofDays(1));
        assertThatThrownBy(() -> useCase.rotate(seed.team(), "system:test"))
                .isInstanceOf(dev.kairos.api.support.problem.ApiException.class);
        org.mockito.Mockito.verify(conflicts, org.mockito.Mockito.times(8)).replace(any(), any(), any(), any(), any());
    }

    private Seed seed() {
        TeamId team = ApiTestFixtures.insertTeam();
        NewSigningKey key = keysets.createSigningKey(team);
        control.sql("""
                INSERT INTO scheduler.team_keysets (team_id, purpose, encrypted_keyset, kek_uri, updated_at)
                VALUES (:team, 'SIGNING', :keyset, :uri, :now)
                """)
                .param("team", team.value())
                .param("keyset", key.sealed().ciphertext())
                .param("uri", key.sealed().kekUri())
                .param("now", ApiTestFixtures.utc(clock.instant()))
                .update();
        return new Seed(team, key);
    }

    private long auditCount(TeamId team, String action) {
        return control.sql("SELECT count(*) FROM scheduler.audit_log WHERE team_id = :team AND action = :action")
                .param("team", team.value())
                .param("action", action)
                .query(Long.class)
                .single();
    }

    private record Seed(TeamId team, NewSigningKey key) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {
        @Bean
        @Primary
        MutableClock signingTestClock() {
            return MutableClock.at(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        }
    }
}
