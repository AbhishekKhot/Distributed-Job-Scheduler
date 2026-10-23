package dev.kairos.api.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.kairos.api.security.AuthenticationResult.Authenticated;
import dev.kairos.api.security.AuthenticationResult.Reason;
import dev.kairos.api.security.AuthenticationResult.Rejected;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.apikey.ApiKeyMatch;
import dev.kairos.security.apikey.ApiKeyService;
import dev.kairos.security.apikey.PresentedApiKey;
import dev.kairos.security.token.AccessTokenClaims;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.security.token.TokenVerification;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a bearer credential into a {@link KairosPrincipal}: a JWT access token (verified by the security library) or
 * a team API key ({@code kairos_} prefix).
 *
 * <p>Access tokens: after the signature, algorithm, issuer, audience and expiry checks, the token's login session
 * ({@code sid}, the refresh-token family) must still be active, the user must be active, and the user's current team
 * must equal the token's team. The session state is cached for at most {@code sessionCacheTtl} (30s or less), so a
 * revoked session or a removed member stops working within that time without a database read per request. A token
 * issued after its cached state was read (a refresh that picked up a new team) re-reads the state once on a team
 * mismatch, so joining or creating a team takes effect at once; older tokens never trigger that re-read.
 *
 * <p>API keys: the key is parsed structurally, its stored row is cached for at most {@code apiKeyCacheTtl} (60s or
 * less), the secret is verified in constant time, and revoked or expired keys are refused. A key hashed under a
 * previous pepper is re-hashed with the current one.
 *
 * <p>Database failures propagate, so the caller fails closed with 503. Thread-safe.
 */
public final class BearerAuthenticator {

    /** The longest session cache lifetime the session check allows (checklist T-201). */
    public static final Duration MAX_SESSION_CACHE_TTL = Duration.ofSeconds(30);

    /** The longest API key cache lifetime allowed (checklist T-201). */
    public static final Duration MAX_API_KEY_CACHE_TTL = Duration.ofSeconds(60);

    private static final Logger LOG = LoggerFactory.getLogger(BearerAuthenticator.class);

    private final AccessTokenService tokens;
    private final ApiKeyService apiKeys;
    private final SessionLookup sessions;
    private final ApiKeyLookup keys;
    private final Clock clock;
    private final Cache<SessionKey, CachedSession> sessionCache;
    private final Cache<String, Optional<ApiKeyLookup.StoredApiKey>> apiKeyCache;

    /**
     * Creates the authenticator.
     *
     * @param tokens verifies access tokens
     * @param apiKeys parses and verifies API keys
     * @param sessions reads session state
     * @param keys reads stored API keys
     * @param clock the time source, also used for cache expiry
     * @param settings the cache settings
     */
    public BearerAuthenticator(
            AccessTokenService tokens,
            ApiKeyService apiKeys,
            SessionLookup sessions,
            ApiKeyLookup keys,
            Clock clock,
            CacheSettings settings) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.apiKeys = Objects.requireNonNull(apiKeys, "apiKeys");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.clock = Objects.requireNonNull(clock, "clock");
        Ticker ticker = () -> {
            Instant now = clock.instant();
            return now.getEpochSecond() * 1_000_000_000L + now.getNano();
        };
        this.sessionCache = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(settings.sessionTtl())
                .maximumSize(settings.maximumSize())
                .build();
        this.apiKeyCache = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(settings.apiKeyTtl())
                .maximumSize(settings.maximumSize())
                .build();
    }

    /**
     * Checks a bearer credential.
     *
     * @param credential the value after {@code Bearer }
     * @return the principal, or why the credential is refused
     */
    public AuthenticationResult authenticate(String credential) {
        if (ApiKeyService.looksLikeApiKey(credential)) {
            return authenticateApiKey(credential);
        }
        return authenticateToken(credential);
    }

    private AuthenticationResult authenticateToken(String credential) {
        TokenVerification verification = tokens.verify(credential);
        if (!(verification instanceof TokenVerification.Valid valid)) {
            if (verification instanceof TokenVerification.Invalid invalid) {
                LOG.debug("Access token rejected: {}", invalid.reason());
            }
            return new Rejected(Reason.INVALID_TOKEN);
        }
        AccessTokenClaims claims = valid.claims();
        SessionKey key = new SessionKey(claims.user(), claims.sessionId());
        CachedSession cached = sessionCache.get(key, this::load);
        if (cached.isStaleFor(claims)) {
            // The token was issued after this state was read and names another team: the cached team predates the
            // refresh that issued it. Read once more; the checks below then use the current state.
            cached = load(key);
            sessionCache.put(key, cached);
        }
        Optional<SessionLookup.SessionState> state = cached.state();
        if (state.isEmpty() || !state.get().sessionActive()) {
            return new Rejected(Reason.SESSION_REVOKED);
        }
        if (!state.get().userActive()) {
            return new Rejected(Reason.USER_DISABLED);
        }
        if (!Objects.equals(state.get().team(), claims.team())) {
            return new Rejected(Reason.TEAM_CHANGED);
        }
        return new Authenticated(new UserPrincipal(claims.user(), claims.team(), claims.sessionId()));
    }

    private CachedSession load(SessionKey key) {
        Instant now = clock.instant();
        return new CachedSession(sessions.find(key.user(), key.sessionId(), now), now);
    }

    private AuthenticationResult authenticateApiKey(String credential) {
        Optional<PresentedApiKey> parsed = apiKeys.parse(credential);
        if (parsed.isEmpty()) {
            return new Rejected(Reason.INVALID_API_KEY);
        }
        PresentedApiKey presented = parsed.get();
        Optional<ApiKeyLookup.StoredApiKey> found = apiKeyCache.get(presented.keyId(), keys::find);
        if (found.isEmpty()) {
            return new Rejected(Reason.INVALID_API_KEY);
        }
        ApiKeyLookup.StoredApiKey stored = found.get();
        ApiKeyMatch match = apiKeys.verify(presented, stored.secretHash(), stored.pepperVersion());
        if (!match.matches()) {
            return new Rejected(Reason.INVALID_API_KEY);
        }
        if (!stored.usableAt(clock.instant())) {
            return new Rejected(Reason.API_KEY_UNUSABLE);
        }
        if (match == ApiKeyMatch.MATCH_OUTDATED_PEPPER) {
            upgrade(presented, stored);
        }
        return new Authenticated(new ApiKeyPrincipal(stored.keyId(), stored.team()));
    }

    private void upgrade(PresentedApiKey presented, ApiKeyLookup.StoredApiKey stored) {
        try {
            keys.upgradeHash(
                    stored.keyId(), stored.pepperVersion(), apiKeys.hash(presented), apiKeys.currentPepperVersion());
            apiKeyCache.invalidate(stored.keyId());
        } catch (RuntimeException e) {
            // The key is valid either way; the upgrade is retried on a later request.
            LOG.warn(
                    "Could not re-hash API key {} under the current pepper: {}",
                    stored.keyId(),
                    e.getClass().getSimpleName());
        }
    }

    /** Drops every cached session and API key, for tests and operator actions. */
    public void invalidateAll() {
        sessionCache.invalidateAll();
        apiKeyCache.invalidateAll();
    }

    /**
     * The cache settings.
     *
     * @param sessionTtl how long a session state is reused, at most 30s
     * @param apiKeyTtl how long a stored API key is reused, at most 60s
     * @param maximumSize the most entries per cache
     */
    public record CacheSettings(Duration sessionTtl, Duration apiKeyTtl, long maximumSize) {

        /**
         * Validates the settings.
         *
         * @throws IllegalArgumentException if a lifetime is negative or above its maximum, or the size is not positive
         */
        public CacheSettings {
            Objects.requireNonNull(sessionTtl, "sessionTtl");
            Objects.requireNonNull(apiKeyTtl, "apiKeyTtl");
            if (sessionTtl.isNegative() || sessionTtl.compareTo(MAX_SESSION_CACHE_TTL) > 0) {
                throw new IllegalArgumentException("the session cache lifetime must be between 0 and 30s");
            }
            if (apiKeyTtl.isNegative() || apiKeyTtl.compareTo(MAX_API_KEY_CACHE_TTL) > 0) {
                throw new IllegalArgumentException("the API key cache lifetime must be between 0 and 60s");
            }
            if (maximumSize < 1) {
                throw new IllegalArgumentException("the cache size must be positive");
            }
        }
    }

    private record SessionKey(UserId user, UUID sessionId) {}

    /** A session state and when it was read. */
    private record CachedSession(Optional<SessionLookup.SessionState> state, Instant loadedAt) {

        /**
         * Whether the state may predate the token: an active session whose team differs from the token's, read no
         * later than the token was issued. {@code iat} has second precision, so the read time is compared to the
         * second; an older token can re-read at most within that one second.
         */
        boolean isStaleFor(AccessTokenClaims claims) {
            return state.isPresent()
                    && state.get().sessionActive()
                    && !Objects.equals(state.get().team(), claims.team())
                    && !claims.issuedAt().isBefore(loadedAt.truncatedTo(ChronoUnit.SECONDS));
        }
    }
}
