package dev.kairos.api.support.ratelimit.infrastructure;

import dev.kairos.engine.lock.RedisProperties;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.io.File;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The API's own Redis connection for rate-limit buckets, with the same settings as the engine's connection
 * ({@code kairos.redis.*}: ACL user, TLS with full verification, command timeout, rejected commands while
 * disconnected).
 *
 * <p>The connection is opened on first use, so the API starts while Redis is down. While it cannot be opened, calls
 * fail at once and a new attempt is made at most every {@code reconnectDelay}; once open, Lettuce reconnects in the
 * background and rejects commands while disconnected. Every failure is reported as
 * {@link RateLimitRedisUnavailableException}, which the limiter turns into 503. Thread-safe; close it on shutdown.
 */
public final class RateLimitRedis implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitRedis.class);

    /** Commands waiting for a connection are bounded; beyond this Lettuce rejects them (fail closed). */
    private static final int REQUEST_QUEUE_SIZE = 10_000;

    /** Buckets are kept in Redis until they would be full again, plus this margin. */
    private static final Duration BUCKET_EXPIRY_MARGIN = Duration.ofSeconds(10);

    private static final RedisCodec<String, byte[]> CODEC = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

    private final RedisClient client;
    private final RedisURI uri;
    private final Duration commandTimeout;
    private final Duration reconnectDelay;
    private final Clock clock;
    private final ReentrantLock connectLock = new ReentrantLock();

    private volatile @Nullable Connected connected;

    // Guarded by connectLock.
    private Instant nextAttemptAt = Instant.MIN;
    private boolean closed;

    private RateLimitRedis(RedisClient client, RedisURI uri, RedisProperties properties, Clock clock) {
        this.client = client;
        this.uri = uri;
        this.commandTimeout = properties.commandTimeout();
        this.reconnectDelay = properties.reconnectDelay();
        this.clock = clock;
    }

    /**
     * Creates the connection holder without connecting.
     *
     * @param properties the Redis settings
     * @param clock the time source for reconnect pacing
     * @return the holder
     * @throws IllegalArgumentException if {@code trustCertificate} is set but is not a readable file
     */
    public static RateLimitRedis create(RedisProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(clock, "clock");
        RedisURI uri = RedisURI.builder()
                .withHost(properties.host())
                .withPort(properties.port())
                .withAuthentication(properties.username(), properties.password())
                .withSsl(properties.tls())
                .withVerifyPeer(SslVerifyMode.FULL)
                .withTimeout(properties.commandTimeout())
                .build();
        RedisClient client = RedisClient.create();
        try {
            client.setOptions(clientOptions(properties));
            return new RateLimitRedis(client, uri, properties, clock);
        } catch (RuntimeException e) {
            client.shutdown();
            throw e;
        }
    }

    private static ClientOptions clientOptions(RedisProperties properties) {
        ClientOptions.Builder options = ClientOptions.builder()
                .autoReconnect(true)
                // Fail closed while disconnected: queued commands would only time out later.
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(REQUEST_QUEUE_SIZE)
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(properties.connectTimeout())
                        .keepAlive(true)
                        .build())
                .timeoutOptions(TimeoutOptions.enabled(properties.commandTimeout()));
        if (properties.tls()) {
            SslOptions.Builder ssl = SslOptions.builder().jdkSslProvider().protocols("TLSv1.3", "TLSv1.2");
            String trustCertificate = properties.trustCertificate();
            if (trustCertificate != null) {
                File file = new File(trustCertificate);
                if (!file.isFile() || !file.canRead()) {
                    throw new IllegalArgumentException("kairos.redis.trust-certificate is not a readable file");
                }
                ssl.trustManager(file);
            }
            options.sslOptions(ssl.build());
        }
        return options.build();
    }

    /**
     * Returns the bucket store, opening the connection if needed.
     *
     * @return the Bucket4j proxy manager keyed by Redis key
     * @throws RateLimitRedisUnavailableException if Redis cannot be reached now
     */
    public ProxyManager<String> buckets() {
        return connection().buckets();
    }

    /**
     * Checks that Redis answers.
     *
     * @return whether a {@code PING} succeeded within the command timeout
     */
    public boolean ping() {
        try {
            return "PONG".equals(connection().connection().sync().ping());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Connected connection() {
        Connected current = connected;
        if (current != null) {
            return current;
        }
        // Only one request connects at a time; the others fail fast instead of piling up behind it.
        if (!connectLock.tryLock()) {
            throw new RateLimitRedisUnavailableException("Redis connection is being opened");
        }
        try {
            current = connected;
            if (current != null) {
                return current;
            }
            if (closed) {
                throw new RateLimitRedisUnavailableException("Redis connection is closed");
            }
            Instant now = clock.instant();
            if (now.isBefore(nextAttemptAt)) {
                throw new RateLimitRedisUnavailableException("Waiting before the next Redis connection attempt");
            }
            try {
                StatefulRedisConnection<String, byte[]> connection = client.connect(CODEC, uri);
                ProxyManager<String> buckets = Bucket4jLettuce.casBasedBuilder(connection)
                        .expirationAfterWrite(
                                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(BUCKET_EXPIRY_MARGIN))
                        .requestTimeout(commandTimeout)
                        .build();
                current = new Connected(connection, buckets);
                connected = current;
                LOG.info("Rate-limit Redis connection opened");
                return current;
            } catch (RuntimeException e) {
                nextAttemptAt = now.plus(reconnectDelay);
                LOG.warn("Rate-limit Redis connection failed: {}", e.getClass().getSimpleName());
                throw new RateLimitRedisUnavailableException("Redis connection failed", e);
            }
        } finally {
            connectLock.unlock();
        }
    }

    /** Closes the connection and shuts the client down. Later calls fail closed. */
    @Override
    public void close() {
        connectLock.lock();
        try {
            closed = true;
            connected = null;
        } finally {
            connectLock.unlock();
        }
        client.shutdown();
    }

    @Override
    public String toString() {
        return "RateLimitRedis[" + uri.getHost() + ":" + uri.getPort() + "]";
    }

    private record Connected(StatefulRedisConnection<String, byte[]> connection, ProxyManager<String> buckets) {}
}
