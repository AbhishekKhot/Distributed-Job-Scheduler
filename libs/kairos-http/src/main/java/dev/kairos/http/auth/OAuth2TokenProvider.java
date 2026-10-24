package dev.kairos.http.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.kairos.domain.retry.CallResult;
import dev.kairos.http.client.HttpCallOutcome;
import dev.kairos.http.client.OutboundHttpClient;
import dev.kairos.http.request.HttpMethod;
import dev.kairos.http.request.OutboundRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Fetches and caches OAuth 2.0 access tokens with the client-credentials grant (RFC 6749 section 4.4).
 *
 * <p>The token request goes through the same {@link OutboundHttpClient} as the target call, so the token endpoint must
 * pass the target policy, the validating resolver and TLS verification too. The client authenticates with HTTP Basic
 * ({@code client_secret_basic}, the form-encoded id and secret). A token is cached under its {@link TokenCacheKey}
 * until 60 seconds before it expires; a response without {@code expires_in} is used once and not cached. Concurrent
 * callers for the same key wait for one fetch instead of each fetching; other keys are not blocked.
 *
 * <p>Thread-safe.
 */
public final class OAuth2TokenProvider {

    /** A cached token is refreshed this long before it expires. */
    public static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    /** The response timeout of a token request. */
    public static final Duration TOKEN_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** The longest lifetime a token is cached for, whatever the token endpoint says. */
    public static final Duration MAX_LIFETIME = Duration.ofHours(24);

    private static final int MAX_TOKEN = 8192;

    private final Clock clock;
    private final Cache<TokenCacheKey, CachedToken> tokens;
    private final Cache<TokenCacheKey, ReentrantLock> locks;

    /**
     * Creates the provider.
     *
     * @param clock the clock that decides expiry
     * @param maxEntries how many tokens are cached
     */
    public OAuth2TokenProvider(Clock clock, int maxEntries) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries is positive");
        }
        this.tokens = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(MAX_LIFETIME)
                .build();
        this.locks = Caffeine.newBuilder().weakValues().build();
    }

    /**
     * Returns a valid access token, from the cache or freshly fetched.
     *
     * @param material the client credentials and token endpoint
     * @param client the client to fetch through
     * @return the access token
     * @throws AuthFailedException if the token endpoint fails or answers with an unusable response
     */
    public String token(AuthMaterial.OAuth2ClientCredentials material, OutboundHttpClient client) {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(client, "client");
        TokenCacheKey key = material.cacheKey();
        Optional<String> cached = valid(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        ReentrantLock lock = locks.get(key, k -> new ReentrantLock());
        lock.lock();
        try {
            cached = valid(key);
            if (cached.isPresent()) {
                return cached.get();
            }
            CachedToken fetched = fetch(material, client);
            if (fetched.expiresAt().minus(REFRESH_MARGIN).isAfter(clock.instant())) {
                tokens.put(key, fetched);
            }
            return fetched.value();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drops a cached token, for example after the target answered 401 with it.
     *
     * @param key the token's key
     */
    public void invalidate(TokenCacheKey key) {
        tokens.invalidate(key);
    }

    private Optional<String> valid(TokenCacheKey key) {
        CachedToken token = tokens.getIfPresent(key);
        if (token == null) {
            return Optional.empty();
        }
        if (!token.expiresAt().minus(REFRESH_MARGIN).isAfter(clock.instant())) {
            tokens.asMap().remove(key, token);
            return Optional.empty();
        }
        return Optional.of(token.value());
    }

    private CachedToken fetch(AuthMaterial.OAuth2ClientCredentials material, OutboundHttpClient client) {
        Instant requestedAt = clock.instant();
        String scope = material.scope();
        String body = "grant_type=client_credentials" + (scope == null ? "" : "&scope=" + formEncode(scope));
        String basic = Base64.getEncoder()
                .encodeToString((formEncode(material.clientId()) + ":" + formEncode(material.clientSecret()))
                        .getBytes(StandardCharsets.UTF_8));
        OutboundRequest request = OutboundRequest.builder(HttpMethod.POST, material.tokenUrl(), TOKEN_REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header(StaticAuthAppliers.AUTHORIZATION, "Basic " + basic)
                .body(body.getBytes(StandardCharsets.UTF_8))
                .build();
        HttpCallOutcome outcome = client.execute(request);
        return switch (outcome) {
            case HttpCallOutcome.Received received -> parse(received, requestedAt);
            case HttpCallOutcome.TooLarge tooLarge ->
                throw new AuthFailedException(
                        new CallResult.ResponseTooLarge(tooLarge.status()), "the token response is too large");
            case HttpCallOutcome.Failed failed ->
                throw new AuthFailedException(unsent(failed.result()), "the token request failed: " + failed.detail());
            case HttpCallOutcome.NotSent notSent ->
                throw new AuthFailedException(new CallResult.ConnectionFailed(false), "the token request was not sent");
            case HttpCallOutcome.Cancelled _ ->
                throw new AuthFailedException(
                        new CallResult.ConnectionFailed(false), "the token request was cancelled");
        };
    }

    private CachedToken parse(HttpCallOutcome.Received received, Instant requestedAt) {
        if (received.status() < 200 || received.status() > 299) {
            throw new AuthFailedException(
                    new CallResult.Responded(received.status()), "the token endpoint refused the request");
        }
        Optional<JsonNode> json = received.body().json();
        if (json.isEmpty() || !json.get().isObject()) {
            throw unusable();
        }
        JsonNode tree = json.get();
        String accessToken = string(tree.get("access_token"));
        String tokenType = string(tree.get("token_type"));
        if (accessToken == null
                || !isTokenText(accessToken)
                || tokenType == null
                || !tokenType.equalsIgnoreCase("bearer")) {
            throw unusable();
        }
        long expiresIn = seconds(tree.get("expires_in"));
        long lifetime = Math.min(expiresIn, MAX_LIFETIME.toSeconds());
        // Expiry counts from when the request started, so the cached lifetime errs on the short side.
        return new CachedToken(accessToken, requestedAt.plusSeconds(lifetime));
    }

    private static @Nullable String string(@Nullable JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    private static long seconds(@Nullable JsonNode node) {
        if (node == null) {
            return 0;
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return Math.max(0, node.longValue());
        }
        // Some token endpoints send expires_in as a string.
        String text = string(node);
        if (text != null
                && !text.isEmpty()
                && text.length() <= 10
                && text.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(text);
        }
        return 0;
    }

    private static boolean isTokenText(String token) {
        if (token.isEmpty() || token.length() > MAX_TOKEN) {
            return false;
        }
        return token.chars().allMatch(c -> c >= 0x21 && c <= 0x7e);
    }

    private static AuthFailedException unusable() {
        return new AuthFailedException(
                new CallResult.RenderFailed(), "the token response is not a usable bearer token");
    }

    /** The target request was never sent, whatever happened to the token request. */
    private static CallResult unsent(CallResult result) {
        return switch (result) {
            case CallResult.TimedOut timedOut -> new CallResult.TimedOut(false);
            case CallResult.ConnectionFailed failed -> new CallResult.ConnectionFailed(false);
            default -> result;
        };
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** A token and its expiry; the value never appears in {@link #toString()}. */
    private record CachedToken(String value, Instant expiresAt) {

        @Override
        public String toString() {
            return "CachedToken[expiresAt=" + expiresAt + "]";
        }
    }
}
