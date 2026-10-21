package dev.kairos.api.config;

import dev.kairos.api.security.ApiKeyLookup;
import dev.kairos.api.security.BearerAuthenticator;
import dev.kairos.api.security.SessionLookup;
import dev.kairos.api.security.infrastructure.JdbcApiKeyLookup;
import dev.kairos.api.security.infrastructure.JdbcRefreshSessionStore;
import dev.kairos.api.security.infrastructure.JdbcSessionLookup;
import dev.kairos.api.support.idempotency.IdempotencyService;
import dev.kairos.api.support.idempotency.IdempotencyStore;
import dev.kairos.api.support.idempotency.infrastructure.JdbcIdempotencyStore;
import dev.kairos.api.support.paging.CursorCodec;
import dev.kairos.api.support.problem.ProblemWriter;
import dev.kairos.api.support.problem.TraceIds;
import dev.kairos.api.support.ratelimit.RateLimiter;
import dev.kairos.api.support.ratelimit.infrastructure.Bucket4jRateLimiter;
import dev.kairos.api.support.ratelimit.infrastructure.RateLimitRedis;
import dev.kairos.api.support.ratelimit.infrastructure.RateLimitRedisHealthIndicator;
import dev.kairos.api.support.web.ClientAddresses;
import dev.kairos.engine.lock.RedisProperties;
import dev.kairos.engine.outbox.OutboxConfiguration;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.apikey.ApiKeyService;
import dev.kairos.security.config.ApiKeyConfiguration;
import dev.kairos.security.config.TokenConfiguration;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.security.token.RefreshSessionStore;
import io.micrometer.tracing.Tracer;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wiring of the API foundation (IMPLEMENTATION_CHECKLIST T-201).
 *
 * <p>Libraries are imported explicitly (section 4.3): {@link OutboxConfiguration} brings the persistence library and
 * the process's single {@code KairosIdFactory}; {@link TokenConfiguration} and {@link ApiKeyConfiguration} bring token
 * and API key verification. This class adds the service's clock and CSPRNG, the problem writer, the credential
 * checks with their SQL adapters, the cursor codec, the rate limiter on its own Redis connection and the idempotency
 * store.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ApiProperties.class, RedisProperties.class})
@Import({OutboxConfiguration.class, TokenConfiguration.class, ApiKeyConfiguration.class})
public class ApiConfiguration {

    /** Creates the configuration; Spring calls it. */
    public ApiConfiguration() {}

    /**
     * The service clock (UTC); every component takes time from it.
     *
     * @return the clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The service CSPRNG for request ids and the security library.
     *
     * @return the random source
     */
    @Bean
    public SecureRandom secureRandom() {
        return new SecureRandom();
    }

    /**
     * Finds the trace id of a request for problem bodies.
     *
     * @param tracer the tracer, if tracing is configured
     * @return the lookup
     */
    @Bean
    public TraceIds traceIds(ObjectProvider<Tracer> tracer) {
        return new TraceIds(tracer);
    }

    /**
     * Builds and writes problem bodies.
     *
     * @param json the JSON mapper
     * @param traceIds finds trace ids
     * @return the writer
     */
    @Bean
    public ProblemWriter problemWriter(JsonMapper json, TraceIds traceIds) {
        return new ProblemWriter(json, traceIds);
    }

    /**
     * Refresh-token sessions on {@code user_sessions}, required by the token configuration.
     *
     * @param control the control database's client
     * @param transactions control-database transactions
     * @return the store
     */
    @Bean
    public RefreshSessionStore refreshSessionStore(
            @Qualifier("controlJdbcClient") JdbcClient control, ControlTransactions transactions) {
        return new JdbcRefreshSessionStore(control, transactions);
    }

    /**
     * Session state for the access-token check.
     *
     * @param control the control database's client
     * @return the lookup
     */
    @Bean
    public SessionLookup sessionLookup(@Qualifier("controlJdbcClient") JdbcClient control) {
        return new JdbcSessionLookup(control);
    }

    /**
     * Stored API keys for the API key check.
     *
     * @param control the control database's client
     * @return the lookup
     */
    @Bean
    public ApiKeyLookup apiKeyLookup(@Qualifier("controlJdbcClient") JdbcClient control) {
        return new JdbcApiKeyLookup(control);
    }

    /**
     * Checks bearer credentials.
     *
     * @param tokens verifies access tokens
     * @param apiKeys verifies API keys
     * @param sessions session state
     * @param keys stored API keys
     * @param clock the clock
     * @param properties the API settings
     * @return the authenticator
     */
    @Bean
    public BearerAuthenticator bearerAuthenticator(
            AccessTokenService tokens,
            ApiKeyService apiKeys,
            SessionLookup sessions,
            ApiKeyLookup keys,
            Clock clock,
            ApiProperties properties) {
        ApiProperties.Auth auth = properties.auth();
        return new BearerAuthenticator(
                tokens,
                apiKeys,
                sessions,
                keys,
                clock,
                new BearerAuthenticator.CacheSettings(
                        auth.sessionCacheTtl(), auth.apiKeyCacheTtl(), auth.cacheMaximumSize()));
    }

    /**
     * Signs and verifies pagination cursors.
     *
     * @param properties the API settings
     * @return the codec
     */
    @Bean
    public CursorCodec cursorCodec(ApiProperties properties) {
        return CursorCodec.fromHex(properties.cursor().hmacKey());
    }

    /**
     * Finds client addresses behind trusted proxies.
     *
     * @param properties the API settings
     * @return the resolver
     */
    @Bean
    public ClientAddresses clientAddresses(ApiProperties properties) {
        return new ClientAddresses(properties.trustedProxies());
    }

    /**
     * The rate-limit Redis connection, opened on first use and closed on shutdown.
     *
     * @param properties the Redis settings ({@code kairos.redis.*})
     * @param clock the clock
     * @return the connection
     */
    @Bean(destroyMethod = "close")
    public RateLimitRedis rateLimitRedis(RedisProperties properties, Clock clock) {
        return RateLimitRedis.create(properties, clock);
    }

    /**
     * The rate limiter.
     *
     * @param redis the Redis connection
     * @param properties the API settings
     * @return the limiter
     */
    @Bean
    public RateLimiter rateLimiter(RateLimitRedis redis, ApiProperties properties) {
        return new Bucket4jRateLimiter(redis, properties.rateLimits().policies());
    }

    /**
     * Readiness of the rate-limit Redis.
     *
     * @param redis the Redis connection
     * @return the health indicator, named {@value RateLimitRedisHealthIndicator#NAME}
     */
    @Bean(name = RateLimitRedisHealthIndicator.NAME)
    public RateLimitRedisHealthIndicator redisHealthIndicator(RateLimitRedis redis) {
        return new RateLimitRedisHealthIndicator(redis);
    }

    /**
     * Idempotency records in the control database and the cells.
     *
     * @param control the control database's client
     * @param cells the cell router
     * @param json the JSON mapper
     * @return the store
     */
    @Bean
    public IdempotencyStore idempotencyStore(
            @Qualifier("controlJdbcClient") JdbcClient control, CellRouter cells, JsonMapper json) {
        return new JdbcIdempotencyStore(control, cells, json);
    }

    /**
     * The idempotency rules.
     *
     * @param store the record store
     * @param clock the clock
     * @param properties the API settings
     * @return the service
     */
    @Bean
    public IdempotencyService idempotencyService(IdempotencyStore store, Clock clock, ApiProperties properties) {
        ApiProperties.Idempotency settings = properties.idempotency();
        return new IdempotencyService(
                store,
                clock,
                new IdempotencyService.Settings(
                        settings.ttl(), settings.staleAfter(), settings.waitTimeout(), settings.pollInterval()),
                IdempotencyService.Sleeper.threadSleep());
    }
}
