package dev.kairos.http.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import dev.kairos.http.dns.HostLookup;
import dev.kairos.http.dns.ValidatingDnsResolver;
import dev.kairos.http.policy.TargetPolicy;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.auth.AuthSchemeFactory;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.io.DetachedSocketFactory;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.ssl.TLS;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.reactor.ssl.SSLBufferMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.jspecify.annotations.Nullable;

/**
 * Builds and owns the outbound HTTP clients: one that trusts the JDK default trust store, and one per team CA bundle
 * that trusts the default store plus that bundle.
 *
 * <p>Every client is a pooled classic Apache HttpClient 5 (safe to call from virtual threads) with: the
 * {@link ValidatingDnsResolver} as its only resolver; an {@code https}-only connection operator; TLS 1.3/1.2 with PKIX
 * chain validation and both the JDK's and HttpClient's hostname verification; a 3s connect and TLS handshake timeout;
 * HTTP/1.1 with at most 100 response headers of 16 KB per line; no proxy; redirects, automatic retries, cookies,
 * authentication challenges and content decompression disabled; per-route and total pool limits.
 *
 * <p>Thread-safe. Closing the factory closes every client and the deadline timer.
 */
public final class HttpClientFactory implements AutoCloseable {

    private static final int MAX_RESPONSE_HEADERS = 100;
    private static final int MAX_HEADER_LINE = 16 * 1024;

    /** Plain TCP sockets; Kairos never uses a proxy. */
    private static final DetachedSocketFactory PLAIN_SOCKETS = proxy -> {
        if (proxy != null) {
            throw new IllegalStateException("outbound calls never use a proxy");
        }
        return new Socket();
    };

    private final TargetPolicy policy;
    private final HostLookup lookup;
    private final HttpClientSettings settings;
    private final Clock clock;
    private final DetachedSocketFactory sockets;
    private final ScheduledThreadPoolExecutor deadlines;
    private final OutboundHttpClient defaultClient;
    private final Cache<String, OutboundHttpClient> bundleClients;

    /**
     * Creates the factory and its default client.
     *
     * @param policy the target policy
     * @param lookup the DNS lookup under the validating resolver
     * @param settings the connection settings
     * @param clock the clock for call timestamps
     */
    public HttpClientFactory(TargetPolicy policy, HostLookup lookup, HttpClientSettings settings, Clock clock) {
        this(policy, lookup, settings, clock, PLAIN_SOCKETS);
    }

    /**
     * Creates the factory with a custom socket source. Package-private: tests use it to route connections for test
     * addresses to a local server; the target policy, resolver and TLS checks still apply unchanged.
     */
    HttpClientFactory(
            TargetPolicy policy,
            HostLookup lookup,
            HttpClientSettings settings,
            Clock clock,
            DetachedSocketFactory sockets) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sockets = Objects.requireNonNull(sockets, "sockets");
        this.deadlines = new ScheduledThreadPoolExecutor(
                1, Thread.ofPlatform().daemon().name("kairos-http-deadline").factory());
        this.deadlines.setRemoveOnCancelPolicy(true);
        this.defaultClient = create(TlsContexts.defaultContext());
        this.bundleClients = Caffeine.newBuilder()
                .maximumSize(settings.maxCaBundleClients())
                // A bundle client unused for an hour is closed; an in-flight call keeps the client it started with.
                .expireAfterAccess(Duration.ofHours(1))
                .removalListener((@Nullable String id, @Nullable OutboundHttpClient client, RemovalCause cause) -> {
                    if (client != null) {
                        client.close();
                    }
                })
                .build();
    }

    /**
     * Returns the client that trusts the JDK default trust store.
     *
     * @return the default client
     */
    public OutboundHttpClient defaultClient() {
        return defaultClient;
    }

    /**
     * Returns the client for a team CA bundle: it trusts the default store plus the bundle. Other teams' clients never
     * trust it.
     *
     * @param bundle the bundle
     * @return the client, created on first use
     */
    public OutboundHttpClient forCaBundle(CaBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        return bundleClients.get(bundle.id(), id -> create(TlsContexts.withBundle(bundle)));
    }

    /** Closes every client and the deadline timer. */
    @Override
    public void close() {
        bundleClients.invalidateAll();
        bundleClients.cleanUp();
        defaultClient.close();
        deadlines.shutdownNow();
    }

    private OutboundHttpClient create(SSLContext sslContext) {
        DefaultClientTlsStrategy tls = new DefaultClientTlsStrategy(
                sslContext,
                TlsContexts.PROTOCOLS,
                null,
                SSLBufferMode.STATIC,
                HostnameVerificationPolicy.BOTH,
                new DefaultHostnameVerifier());
        Timeout connectTimeout =
                Timeout.ofMilliseconds(settings.connectTimeout().toMillis());
        PoolingHttpClientConnectionManager connections = new PolicyConnectionManagerBuilder(sockets)
                .setTlsSocketStrategy(tls)
                .setDnsResolver(new ValidatingDnsResolver(policy, lookup))
                .setSchemePortResolver(DefaultSchemePortResolver.INSTANCE)
                .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.STRICT)
                .setConnPoolPolicy(PoolReusePolicy.LIFO)
                .setMaxConnTotal(settings.maxConnectionsTotal())
                .setMaxConnPerRoute(settings.maxConnectionsPerRoute())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(connectTimeout)
                        .setValidateAfterInactivity(TimeValue.ofSeconds(1))
                        .setTimeToLive(TimeValue.ofMinutes(5))
                        .build())
                .setDefaultTlsConfig(TlsConfig.custom()
                        .setHandshakeTimeout(connectTimeout)
                        .setSupportedProtocols(TLS.V_1_3, TLS.V_1_2)
                        .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_1)
                        .build())
                .setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
                        .http1Config(Http1Config.custom()
                                .setMaxHeaderCount(MAX_RESPONSE_HEADERS)
                                .setMaxLineLength(MAX_HEADER_LINE)
                                .build())
                        .build())
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connections)
                .setRequestExecutor(new ObservingRequestExecutor())
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .setDefaultCredentialsProvider(new BasicCredentialsProvider())
                .setDefaultAuthSchemeRegistry(
                        RegistryBuilder.<AuthSchemeFactory>create().build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableContentCompression()
                .disableConnectionState()
                .build();
        return new OutboundHttpClient(
                client,
                policy,
                deadlines,
                clock,
                Timeout.ofMilliseconds(settings.poolWaitTimeout().toMillis()),
                new ResponseReader());
    }
}
