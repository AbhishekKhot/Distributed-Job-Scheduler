package dev.kairos.engine.lock;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.File;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One Lettuce connection to Redis, shared by the run lock, the sent markers and the topology probe, which it creates.
 *
 * <p>Connection settings (LLD section 4.3, IMPLEMENTATION_CHECKLIST T-111):
 *
 * <ul>
 *   <li>the ACL user and password from {@link RedisProperties}, TLS with full certificate verification when enabled;
 *   <li>every command times out after {@code commandTimeout} (500 ms by default);
 *   <li>while the connection is down, commands are rejected at once instead of being queued, and Lettuce reconnects
 *       in the background. Together with the timeout this makes every operation fail closed quickly.
 * </ul>
 *
 * <p>The connection is opened in the background: {@link #connect()} starts it, and a call made while it is still being
 * opened waits at most {@code commandTimeout} for it. If the first connection fails, the next attempt starts at the
 * earliest {@code reconnectDelay} later, so an unreachable Redis is not hammered with connection attempts.
 *
 * <p>Lua scripts are loaded with {@code SCRIPT LOAD} on first use and invoked by SHA. When Redis answers
 * {@code NOSCRIPT} (it restarted or failed over and lost its script cache), the script is loaded again and the call
 * repeated once.
 *
 * <p>Thread-safe. Close it on shutdown to release the connection and Lettuce's threads.
 */
public final class RedisConnector implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(RedisConnector.class.getName());

    /** Commands waiting for a connection are bounded; beyond this Lettuce rejects them (fail closed). */
    private static final int REQUEST_QUEUE_SIZE = 10_000;

    private final RedisClient client;
    private final RedisURI uri;
    private final Duration commandTimeout;
    private final Duration reconnectDelay;
    private final Clock clock;
    private final RedisMetrics metrics;
    private final ConcurrentMap<LuaScript, String> shas = new ConcurrentHashMap<>();
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final ReentrantLock connectLock = new ReentrantLock();

    private volatile @Nullable StatefulRedisConnection<String, String> connection;

    // Guarded by connectLock.
    private @Nullable Attempt attempt;
    private long attempts;
    private Instant nextAttemptAt = Instant.MIN;
    private boolean closed;

    private RedisConnector(
            RedisClient client, RedisURI uri, RedisProperties properties, MeterRegistry registry, Clock clock) {
        this.client = client;
        this.uri = uri;
        this.commandTimeout = properties.commandTimeout();
        this.reconnectDelay = properties.reconnectDelay();
        this.clock = clock;
        this.metrics = new RedisMetrics(registry);
    }

    /**
     * Creates the connector without connecting yet; call {@link #connect()} to start connecting early.
     *
     * @param properties the connection settings
     * @param registry where the meters are registered
     * @param clock the time source for reconnect pacing
     * @return the connector
     * @throws IllegalArgumentException if {@code trustCertificate} is set but is not a readable file
     */
    public static RedisConnector create(RedisProperties properties, MeterRegistry registry, Clock clock) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(registry, "registry");
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
            return new RedisConnector(client, uri, properties, registry, clock);
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
        String trustCertificate = properties.trustCertificate();
        if (properties.tls()) {
            SslOptions.Builder ssl = SslOptions.builder().jdkSslProvider().protocols("TLSv1.3", "TLSv1.2");
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
     * Starts opening the connection in the background, if it is not open or being opened. Never throws and never
     * blocks; a failure is retried on a later call.
     */
    public void connect() {
        connectLock.lock();
        try {
            if (connection == null
                    && attempt == null
                    && !closed
                    && !clock.instant().isBefore(nextAttemptAt)) {
                attempt = startAttempt();
            }
        } finally {
            connectLock.unlock();
        }
    }

    /**
     * Creates the run lock adapter on this connection.
     *
     * @param nodeId this node's id, written into lock tokens
     * @return the run lock
     * @throws IllegalArgumentException if the node id is malformed
     */
    public RunLockPort runLock(String nodeId) {
        return new LettuceRunLock(this, LockToken.requireNodeId(nodeId), new SecureRandom());
    }

    /**
     * Creates the sent marker adapter on this connection.
     *
     * @return the sent markers
     */
    public SentMarkerPort sentMarkers() {
        return new LettuceSentMarkers(this);
    }

    /**
     * Creates the topology probe on this connection.
     *
     * @return the probe
     */
    public RedisTopologyProbe topologyProbe() {
        return new LettuceTopologyProbe(this);
    }

    /** Closes the connection and shuts the client down. Later calls fail closed. */
    @Override
    public void close() {
        connectLock.lock();
        try {
            closed = true;
            connection = null;
            attempt = null;
        } finally {
            connectLock.unlock();
        }
        // Closes every connection the client opened, including one still being opened.
        client.shutdown();
    }

    RedisMetrics metrics() {
        return metrics;
    }

    /**
     * Runs Redis commands.
     *
     * @throws RedisUnavailableException if Redis is not connected, fails, rejects the command or times out; counted
     */
    <T> T call(Function<RedisCommands<String, String>, T> operation) {
        T result;
        try {
            result = operation.apply(connection().sync());
        } catch (RedisUnavailableException e) {
            throw unavailable(e);
        } catch (RuntimeException e) {
            // Any Lettuce failure (connection, timeout, rejected command, server error) counts as unavailable.
            throw unavailable(new RedisUnavailableException("Redis command failed", e));
        }
        if (available.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.INFO, "Redis is reachable again");
        }
        return result;
    }

    /**
     * Runs a Lua script on one key and returns its integer reply, timing it.
     *
     * @throws RedisUnavailableException if Redis is not connected, fails or times out; counted
     */
    long evalInteger(LuaScript script, String key, String... args) {
        Timer.Sample sample = metrics.startScript();
        try {
            return call(commands -> evalInteger(commands, script, key, args));
        } finally {
            metrics.stopScript(sample, script);
        }
    }

    private long evalInteger(RedisCommands<String, String> commands, LuaScript script, String key, String[] args) {
        String[] keys = {key};
        String sha = shas.get(script);
        if (sha == null) {
            sha = load(commands, script);
        }
        Long reply;
        try {
            reply = commands.evalsha(sha, ScriptOutputType.INTEGER, keys, args);
        } catch (RedisNoScriptException e) {
            // Redis restarted or failed over and lost its script cache: load again and retry once.
            reply = commands.evalsha(load(commands, script), ScriptOutputType.INTEGER, keys, args);
        }
        if (reply == null) {
            throw new RedisUnavailableException("Script " + script.label() + " returned no reply");
        }
        return reply;
    }

    private String load(RedisCommands<String, String> commands, LuaScript script) {
        String sha = commands.scriptLoad(script.source());
        shas.put(script, sha);
        return sha;
    }

    private RedisUnavailableException unavailable(RedisUnavailableException e) {
        metrics.unavailable();
        if (available.compareAndSet(true, false)) {
            // The cause's class is enough to diagnose; messages are kept out of the log on purpose.
            Throwable cause = e.getCause();
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Redis is unavailable; run lock and sent marker operations fail closed ({0}: {1})",
                    e.getMessage(),
                    cause == null ? "no cause" : cause.getClass().getSimpleName());
        }
        return e;
    }

    private StatefulRedisConnection<String, String> connection() {
        StatefulRedisConnection<String, String> current = connection;
        if (current != null) {
            return current;
        }
        Attempt pending = pendingAttempt();
        try {
            current = pending.future().get(commandTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new RedisUnavailableException("Redis connection is still being opened", e);
        } catch (ExecutionException e) {
            attemptFailed(pending);
            throw new RedisUnavailableException("Redis connection failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RedisUnavailableException("Interrupted while connecting to Redis", e);
        }
        attemptSucceeded(pending, current);
        return current;
    }

    private Attempt pendingAttempt() {
        connectLock.lock();
        try {
            Attempt pending = attempt;
            if (pending != null) {
                return pending;
            }
            if (closed) {
                throw new RedisUnavailableException("Redis connector is closed");
            }
            if (clock.instant().isBefore(nextAttemptAt)) {
                throw new RedisUnavailableException("Waiting before the next Redis connection attempt");
            }
            Attempt started = startAttempt();
            attempt = started;
            return started;
        } finally {
            connectLock.unlock();
        }
    }

    // Called with connectLock held, when no attempt is pending and the connector is open; the caller stores it.
    private Attempt startAttempt() {
        CompletableFuture<StatefulRedisConnection<String, String>> future;
        try {
            future = client.connectAsync(StringCodec.UTF8, uri).toCompletableFuture();
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        attempts++;
        return new Attempt(attempts, future);
    }

    private void attemptSucceeded(Attempt pending, StatefulRedisConnection<String, String> opened) {
        connectLock.lock();
        try {
            if (isCurrent(pending) && !closed) {
                connection = opened;
                attempt = null;
            }
        } finally {
            connectLock.unlock();
        }
    }

    private void attemptFailed(Attempt pending) {
        connectLock.lock();
        try {
            if (isCurrent(pending)) {
                attempt = null;
                nextAttemptAt = clock.instant().plus(reconnectDelay);
            }
        } finally {
            connectLock.unlock();
        }
    }

    // Called with connectLock held.
    private boolean isCurrent(Attempt pending) {
        Attempt current = attempt;
        return current != null && current.number() == pending.number();
    }

    /** One connection attempt, numbered so that a late completion of an older attempt is recognised. */
    private record Attempt(long number, CompletableFuture<StatefulRedisConnection<String, String>> future) {}
}
