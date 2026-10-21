package dev.kairos.api.security.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.security.ApiKeyLookup;
import dev.kairos.api.security.SessionLookup;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.token.RefreshSession;
import dev.kairos.security.token.RefreshSessionStatus;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The SQL adapters of the security ports against the real control schema. */
@KairosIntegrationTest(kafka = false, redis = false)
class SecurityAdaptersIntegrationTest {

    private static JdbcClient control;
    private static ControlTransactions transactions;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @BeforeAll
    static void databases() {
        KairosPostgres postgres = ApiTestFixtures.ensureDatabases();
        control = JdbcClient.create(postgres.controlDataSource());
        transactions = new ControlTransactions(Database.of(
                Database.CONTROL, postgres.controlDataSource(), DatabaseOptions.defaults(), new SimpleMeterRegistry()));
    }

    @Test
    void sessionLookupReportsTheSessionTheUserAndTheTeam() {
        TeamId team = ApiTestFixtures.insertTeam();
        UserId user = ApiTestFixtures.insertUser(team);
        UUID family = ApiTestFixtures.insertSession(user);
        SessionLookup lookup = new JdbcSessionLookup(control);

        assertThat(lookup.find(user, family, now)).contains(new SessionLookup.SessionState(true, true, team));
        assertThat(lookup.find(user, UUID.randomUUID(), now))
                .contains(new SessionLookup.SessionState(false, true, team));
        assertThat(lookup.find(user, family, now.plus(Duration.ofDays(2))))
                .contains(new SessionLookup.SessionState(false, true, team));
        assertThat(lookup.find(TestIds.userId(), family, now)).isEmpty();

        control.sql("UPDATE scheduler.users SET status = 'DISABLED', team_id = NULL WHERE user_id = :user")
                .param("user", user.value())
                .update();
        assertThat(lookup.find(user, family, now)).contains(new SessionLookup.SessionState(true, false, null));
    }

    @Test
    void apiKeyLookupFindsAndUpgradesKeys() {
        TeamId team = ApiTestFixtures.insertTeam();
        UserId user = ApiTestFixtures.insertUser(team);
        String keyId = "K" + ApiTestFixtures.randomHex().substring(0, 15);
        byte[] hash = random32();
        ApiTestFixtures.insertApiKey(keyId, team, user, hash, 1);
        ApiKeyLookup lookup = new JdbcApiKeyLookup(control);

        ApiKeyLookup.StoredApiKey stored = lookup.find(keyId).orElseThrow();
        assertThat(stored.team()).isEqualTo(team);
        assertThat(stored.secretHash()).isEqualTo(hash);
        assertThat(stored.pepperVersion()).isEqualTo(1);
        assertThat(stored.usableAt(now)).isTrue();
        assertThat(stored.toString()).doesNotContain(java.util.HexFormat.of().formatHex(hash));

        byte[] upgraded = random32();
        lookup.upgradeHash(keyId, 1, upgraded, 2);
        lookup.upgradeHash(keyId, 1, random32(), 3);
        ApiKeyLookup.StoredApiKey after = lookup.find(keyId).orElseThrow();
        assertThat(after.secretHash()).isEqualTo(upgraded);
        assertThat(after.pepperVersion()).isEqualTo(2);
        assertThat(lookup.find("Nnonexistentkey1")).isEmpty();
    }

    @Test
    void refreshSessionsRotateOnceAndRevokeByFamilyAndUser() {
        UserId user = ApiTestFixtures.insertUser(null);
        JdbcRefreshSessionStore store = new JdbcRefreshSessionStore(control, transactions);
        UUID family = UUID.randomUUID();
        RefreshSession first = session(user, family, "203.0.113.9");
        store.insert(first);

        assertThat(store.findByTokenHash(first.tokenHash())).contains(first);

        RefreshSession second = session(user, family, null);
        assertThat(store.rotate(first, second, now)).isTrue();
        assertThat(store.rotate(first, session(user, family, null), now)).isFalse();
        assertThat(store.findByTokenHash(first.tokenHash()).orElseThrow().status())
                .isEqualTo(RefreshSessionStatus.ROTATED);

        UUID otherFamily = UUID.randomUUID();
        store.insert(session(user, otherFamily, null));
        assertThat(store.revokeUser(user, otherFamily)).isEqualTo(2);
        assertThat(store.revokeFamily(otherFamily)).isEqualTo(1);
        assertThat(store.revokeUser(user, null)).isZero();
        assertThat(store.findByTokenHash(second.tokenHash()).orElseThrow().status())
                .isEqualTo(RefreshSessionStatus.REVOKED);
    }

    private RefreshSession session(UserId user, UUID family, String ip) {
        return new RefreshSession(
                UUID.randomUUID(),
                user,
                family,
                random32(),
                RefreshSessionStatus.ACTIVE,
                now,
                now.plus(Duration.ofDays(30)),
                "test-agent",
                ip);
    }

    private static byte[] random32() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
