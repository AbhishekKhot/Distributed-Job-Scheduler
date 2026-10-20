package dev.kairos.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.retry.CallResult;
import dev.kairos.http.auth.ApiKeyLocation;
import dev.kairos.http.auth.AuthFailedException;
import dev.kairos.http.auth.AuthMaterial;
import dev.kairos.http.auth.Authenticator;
import dev.kairos.http.auth.OAuth2TokenProvider;
import dev.kairos.http.auth.TokenCacheKey;
import dev.kairos.http.request.HttpMethod;
import dev.kairos.http.request.OutboundRequest;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Target authentication against a local TLS server, including the OAuth 2.0 token endpoint and its cache. */
class AuthenticatorTlsTest {

    private static final AtomicInteger TOKENS_ISSUED = new AtomicInteger();
    private static TlsTestEnvironment env;

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-20T10:00:00Z"));
    private HttpClientFactory factory;
    private OutboundHttpClient client;
    private Authenticator authenticator;

    @BeforeAll
    static void startServer() throws IOException {
        env = new TlsTestEnvironment();
        env.server.handle("/protected", exchange -> TestTlsServer.respond(exchange, 200, "text/plain", "ok"));
        env.server.handle("/token", exchange -> {
            int n = TOKENS_ISSUED.incrementAndGet();
            TestTlsServer.respond(
                    exchange,
                    200,
                    "application/json",
                    "{\"access_token\":\"tok-" + n + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        });
        env.server.handle(
                "/token-short",
                exchange -> TestTlsServer.respond(
                        exchange, 200, "application/json", "{\"access_token\":\"short\",\"token_type\":\"bearer\"}"));
        env.server.handle(
                "/token-denied",
                exchange -> TestTlsServer.respond(exchange, 401, "application/json", "{\"error\":\"invalid_client\"}"));
        env.server.handle(
                "/token-mac",
                exchange -> TestTlsServer.respond(
                        exchange, 200, "application/json", "{\"access_token\":\"m\",\"token_type\":\"mac\"}"));
    }

    @AfterAll
    static void stopServer() {
        env.close();
    }

    @BeforeEach
    void setUp() {
        factory = env.factory(clock);
        client = factory.forCaBundle(env.bundle("team-a/ca"));
        authenticator = new Authenticator(new OAuth2TokenProvider(clock, 100));
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    @Test
    void noneLeavesTheRequestAlone() {
        OutboundRequest request = request("/protected");

        assertThat(authenticator.apply(request, new AuthMaterial.None(), client))
                .isSameAs(request);
    }

    @Test
    void basicAndBearerReplaceAnyTemplatedAuthorizationHeader() {
        OutboundRequest templated = OutboundRequest.builder(
                        HttpMethod.GET,
                        URI.create(env.url("orders.test.internal", "/protected")),
                        Duration.ofSeconds(5))
                .header("authorization", "Bearer from-template")
                .build();

        OutboundRequest basic = authenticator.apply(templated, new AuthMaterial.Basic("svc", "pässword"), client);
        OutboundRequest bearer = authenticator.apply(templated, new AuthMaterial.Bearer("abc.def"), client);

        String expected =
                "Basic " + Base64.getEncoder().encodeToString("svc:pässword".getBytes(StandardCharsets.UTF_8));
        assertThat(basic.headers()).hasSize(1);
        assertThat(basic.header("Authorization")).contains(expected);
        assertThat(bearer.header("Authorization")).contains("Bearer abc.def");
        client.execute(bearer);
        assertThat(env.server.last("/protected").headers().getFirst("Authorization"))
                .isEqualTo("Bearer abc.def");
    }

    @Test
    void apiKeyGoesInAHeaderOrAnEncodedQueryParameter() {
        OutboundRequest header = authenticator.apply(
                request("/protected"), new AuthMaterial.ApiKey(ApiKeyLocation.HEADER, "X-Api-Key", "k1"), client);
        OutboundRequest query = authenticator.apply(
                request("/protected?x=1"), new AuthMaterial.ApiKey(ApiKeyLocation.QUERY, "api_key", "k&2=3"), client);

        assertThat(header.header("x-api-key")).contains("k1");
        client.execute(query);
        assertThat(env.server.last("/protected").rawQuery()).isEqualTo("x=1&api_key=k%262%3D3");
    }

    @Test
    void oauth2FetchesOnceAndRefreshesSixtySecondsBeforeExpiry() {
        AuthMaterial.OAuth2ClientCredentials material = oauth("/token", key(1));
        int before = TOKENS_ISSUED.get();

        OutboundRequest first = authenticator.apply(request("/protected"), material, client);
        OutboundRequest second = authenticator.apply(request("/protected"), material, client);
        clock.advance(Duration.ofSeconds(3600 - 61));
        OutboundRequest stillCached = authenticator.apply(request("/protected"), material, client);
        clock.advance(Duration.ofSeconds(1));
        OutboundRequest refreshed = authenticator.apply(request("/protected"), material, client);

        assertThat(TOKENS_ISSUED.get() - before).isEqualTo(2);
        assertThat(first.header("Authorization")).isEqualTo(second.header("Authorization"));
        assertThat(stillCached.header("Authorization")).isEqualTo(first.header("Authorization"));
        assertThat(refreshed.header("Authorization")).isNotEqualTo(first.header("Authorization"));
        TestTlsServer.Recorded tokenRequest = env.server.last("/token");
        assertThat(tokenRequest.method()).isEqualTo("POST");
        assertThat(tokenRequest.body()).isEqualTo("grant_type=client_credentials&scope=orders%3Awrite");
        assertThat(tokenRequest.headers().getFirst("Authorization"))
                .isEqualTo("Basic "
                        + Base64.getEncoder().encodeToString("client-1:s%3Acret".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void tokensAreCachedPerSecretVersionAndInvalidatedOn401() {
        int before = TOKENS_ISSUED.get();
        AuthMaterial.OAuth2ClientCredentials v1 = oauth("/token", key(1));
        AuthMaterial.OAuth2ClientCredentials v2 = oauth("/token", key(2));

        authenticator.apply(request("/protected"), v1, client);
        authenticator.apply(request("/protected"), v2, client);
        authenticator.onUnauthorized(v1);
        authenticator.onUnauthorized(new AuthMaterial.None());
        authenticator.apply(request("/protected"), v1, client);
        authenticator.apply(request("/protected"), v2, client);

        assertThat(TOKENS_ISSUED.get() - before).isEqualTo(3);
    }

    @Test
    void aTokenWithoutExpiryIsNotCached() {
        AuthMaterial.OAuth2ClientCredentials material = oauth("/token-short", key(1));

        authenticator.apply(request("/protected"), material, client);
        authenticator.apply(request("/protected"), material, client);

        assertThat(env.server.hits("/token-short")).isEqualTo(2);
    }

    @Test
    void aRefusedTokenRequestReportsTheTokenEndpointStatus() {
        assertThatThrownBy(() -> authenticator.apply(request("/protected"), oauth("/token-denied", key(1)), client))
                .isInstanceOf(AuthFailedException.class)
                .satisfies(e ->
                        assertThat(((AuthFailedException) e).callResult()).isEqualTo(new CallResult.Responded(401)))
                .hasMessageNotContaining("s:cret");
        assertThatThrownBy(() -> authenticator.apply(request("/protected"), oauth("/token-mac", key(1)), client))
                .isInstanceOf(AuthFailedException.class)
                .satisfies(e ->
                        assertThat(((AuthFailedException) e).callResult()).isEqualTo(new CallResult.RenderFailed()));
    }

    @Test
    void theTokenEndpointMustPassTheTargetPolicy() {
        AuthMaterial.OAuth2ClientCredentials material = new AuthMaterial.OAuth2ClientCredentials(
                URI.create(env.url("loop.test.internal", "/token")), null, "client-1", "secret", key(1));

        assertThatThrownBy(() -> authenticator.apply(request("/protected"), material, client))
                .isInstanceOf(AuthFailedException.class)
                .satisfies(e -> assertThat(((AuthFailedException) e).callResult())
                        .isEqualTo(new CallResult.TargetNotAllowed()));
    }

    @Test
    void aTokenEndpointNetworkFailureMeansTheTargetWasNotSent() {
        AuthMaterial.OAuth2ClientCredentials material = new AuthMaterial.OAuth2ClientCredentials(
                URI.create(env.url("closed.test.internal", "/token")), null, "client-1", "secret", key(1));

        assertThatThrownBy(() -> authenticator.apply(request("/protected"), material, client))
                .isInstanceOf(AuthFailedException.class)
                .satisfies(e -> assertThat(((AuthFailedException) e).callResult())
                        .isEqualTo(new CallResult.ConnectionFailed(false)));
    }

    private AuthMaterial.OAuth2ClientCredentials oauth(String path, TokenCacheKey key) {
        return new AuthMaterial.OAuth2ClientCredentials(
                URI.create(env.url("token.test.internal", path)), "orders:write", "client-1", "s:cret", key);
    }

    private static final TeamId TEAM = TestIds.teamId();
    private static final EndpointId ENDPOINT = TestIds.endpointId();

    private static TokenCacheKey key(int secretVersion) {
        return new TokenCacheKey(TEAM, ENDPOINT, 3, secretVersion);
    }

    private static OutboundRequest request(String path) {
        return OutboundRequest.builder(
                        HttpMethod.GET, URI.create(env.url("orders.test.internal", path)), Duration.ofSeconds(5))
                .build();
    }
}
