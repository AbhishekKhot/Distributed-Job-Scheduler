package dev.kairos.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.security.AuthenticationResult.Authenticated;
import dev.kairos.api.security.AuthenticationResult.Reason;
import dev.kairos.api.security.AuthenticationResult.Rejected;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.apikey.ApiKeyPeppers;
import dev.kairos.security.apikey.ApiKeyService;
import dev.kairos.security.apikey.IssuedApiKey;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.LocalKeyEncryptionKey;
import dev.kairos.security.keyset.PlatformKeyPurpose;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.security.token.JwtKeyRing;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BearerAuthenticatorTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JwtKeyRing KEYS = keyRing();

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-21T10:00:00Z"));
    private final AccessTokenService tokens = new AccessTokenService(
            KEYS,
            new AccessTokenService.Settings("kairos", "kairos-api", Duration.ofMinutes(15), Duration.ofSeconds(30)),
            clock,
            RANDOM);
    private final String currentPepper = ApiTestFixtures.randomHex();
    private final String oldPepper = ApiTestFixtures.randomHex();
    private final ApiKeyService apiKeys = new ApiKeyService(ApiKeyPeppers.of(2, currentPepper, 1, oldPepper), RANDOM);
    private final FakeSessions sessions = new FakeSessions();
    private final FakeKeys keys = new FakeKeys();
    private final BearerAuthenticator authenticator = new BearerAuthenticator(
            tokens,
            apiKeys,
            sessions,
            keys,
            clock,
            new BearerAuthenticator.CacheSettings(Duration.ofSeconds(30), Duration.ofSeconds(60), 1000));

    private final TeamId team = TestIds.teamId();
    private final UserId user = TestIds.userId();
    private final UUID session = UUID.randomUUID();

    @Test
    void aValidTokenWithAnActiveSessionAuthenticatesTheUser() {
        sessions.state = new SessionLookup.SessionState(true, true, team);

        assertThat(authenticator.authenticate(token(team)))
                .isEqualTo(new Authenticated(new UserPrincipal(user, team, session)));
    }

    @Test
    void anInvalidTokenIsRejected() {
        assertThat(authenticator.authenticate("eyJhbGciOiJub25lIn0.e30."))
                .isEqualTo(new Rejected(Reason.INVALID_TOKEN));
        assertThat(sessions.reads.get()).isZero();
    }

    @Test
    void sessionStateIsCachedForAtMostTheConfiguredTime() {
        sessions.state = new SessionLookup.SessionState(true, true, team);
        String token = token(team);
        authenticator.authenticate(token);

        sessions.state = new SessionLookup.SessionState(false, true, team);
        assertThat(authenticator.authenticate(token)).isInstanceOf(Authenticated.class);
        assertThat(sessions.reads.get()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(31));
        assertThat(authenticator.authenticate(token)).isEqualTo(new Rejected(Reason.SESSION_REVOKED));
        assertThat(sessions.reads.get()).isEqualTo(2);
    }

    @Test
    void disabledUsersUnknownUsersAndTeamChangesAreRejected() {
        sessions.state = new SessionLookup.SessionState(true, false, team);
        assertThat(authenticator.authenticate(token(team))).isEqualTo(new Rejected(Reason.USER_DISABLED));

        authenticator.invalidateAll();
        sessions.state = new SessionLookup.SessionState(true, true, TestIds.teamId());
        assertThat(authenticator.authenticate(token(team))).isEqualTo(new Rejected(Reason.TEAM_CHANGED));

        authenticator.invalidateAll();
        sessions.state = new SessionLookup.SessionState(true, true, team);
        assertThat(authenticator.authenticate(token(null))).isEqualTo(new Rejected(Reason.TEAM_CHANGED));

        authenticator.invalidateAll();
        sessions.state = null;
        assertThat(authenticator.authenticate(token(team))).isEqualTo(new Rejected(Reason.SESSION_REVOKED));
    }

    @Test
    void aUserWithoutATeamAuthenticatesWithoutATeam() {
        sessions.state = new SessionLookup.SessionState(true, true, null);

        AuthenticationResult result = authenticator.authenticate(token(null));

        assertThat(result)
                .isInstanceOfSatisfying(
                        Authenticated.class,
                        authenticated ->
                                assertThat(authenticated.principal().team()).isEmpty());
    }

    @Test
    void anApiKeyAuthenticatesItsTeam() {
        IssuedApiKey key = apiKeys.issue();
        keys.store(key, team, null, null);

        assertThat(authenticator.authenticate(key.key().reveal()))
                .isEqualTo(new Authenticated(new ApiKeyPrincipal(key.keyId(), team)));
    }

    @Test
    void wrongUnknownRevokedAndExpiredKeysAreRejected() {
        IssuedApiKey key = apiKeys.issue();
        String presented = key.key().reveal();
        assertThat(authenticator.authenticate(presented)).isEqualTo(new Rejected(Reason.INVALID_API_KEY));
        assertThat(authenticator.authenticate("kairos_malformed")).isEqualTo(new Rejected(Reason.INVALID_API_KEY));

        authenticator.invalidateAll();
        keys.store(key, team, null, clock.instant());
        assertThat(authenticator.authenticate(presented)).isEqualTo(new Rejected(Reason.API_KEY_UNUSABLE));

        authenticator.invalidateAll();
        keys.store(key, team, clock.instant(), null);
        assertThat(authenticator.authenticate(presented)).isEqualTo(new Rejected(Reason.API_KEY_UNUSABLE));

        IssuedApiKey other = apiKeys.issue();
        keys.store(other, team, null, null);
        String wrong = "kairos_" + other.keyId() + presented.substring(("kairos_" + key.keyId()).length());
        assertThat(authenticator.authenticate(wrong)).isEqualTo(new Rejected(Reason.INVALID_API_KEY));
    }

    @Test
    void aKeyHashedWithThePreviousPepperIsUpgraded() {
        ApiKeyService old = new ApiKeyService(ApiKeyPeppers.of(1, oldPepper, null, null), RANDOM);
        IssuedApiKey key = old.issue();
        keys.store(key, team, null, null);

        assertThat(authenticator.authenticate(key.key().reveal())).isInstanceOf(Authenticated.class);
        assertThat(keys.keys.get(key.keyId()).pepperVersion()).isEqualTo(2);
        assertThat(authenticator.authenticate(key.key().reveal())).isInstanceOf(Authenticated.class);
    }

    @Test
    void databaseFailuresPropagate() {
        sessions.failure = new IllegalStateException("database down");

        assertThatThrownBy(() -> authenticator.authenticate(token(team))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cacheLifetimesAreBounded() {
        assertThatThrownBy(
                        () -> new BearerAuthenticator.CacheSettings(Duration.ofSeconds(31), Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new BearerAuthenticator.CacheSettings(Duration.ofSeconds(1), Duration.ofSeconds(61), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BearerAuthenticator.CacheSettings(Duration.ofSeconds(1), Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String token(TeamId tokenTeam) {
        return tokens.issue(user, tokenTeam, session).token().reveal();
    }

    private static JwtKeyRing keyRing() {
        KeysetService keysets = new KeysetService(
                LocalKeyEncryptionKey.forLocalProfile(ApiTestFixtures.randomHex(), true),
                RANDOM,
                Duration.ofMinutes(1),
                10);
        return JwtKeyRing.fromKeyset(
                keysets.openPlatformKeyset(PlatformKeyPurpose.JWT_SIGNING, keysets.createJwtSigningKeyset()));
    }

    private static final class FakeSessions implements SessionLookup {

        private SessionState state;
        private RuntimeException failure;
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public Optional<SessionState> find(UserId user, UUID sessionId, Instant now) {
            reads.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(state);
        }
    }

    private static final class FakeKeys implements ApiKeyLookup {

        private final Map<String, StoredApiKey> keys = new HashMap<>();

        void store(IssuedApiKey key, TeamId team, Instant revokedAt, Instant expiresAt) {
            keys.put(
                    key.keyId(),
                    new StoredApiKey(key.keyId(), team, key.secretHash(), key.pepperVersion(), expiresAt, revokedAt));
        }

        @Override
        public Optional<StoredApiKey> find(String keyId) {
            return Optional.ofNullable(keys.get(keyId));
        }

        @Override
        public void upgradeHash(String keyId, int oldPepperVersion, byte[] newHash, int newPepperVersion) {
            StoredApiKey current = keys.get(keyId);
            if (current.pepperVersion() == oldPepperVersion) {
                keys.put(
                        keyId,
                        new StoredApiKey(
                                keyId,
                                current.team(),
                                newHash,
                                newPepperVersion,
                                current.expiresAt(),
                                current.revokedAt()));
            }
        }
    }
}
