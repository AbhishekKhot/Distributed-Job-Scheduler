package dev.kairos.engine.lock;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.run.Fence;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link RunLockPort} on Lettuce: each lock operation is one of the {@link LuaScript}s, and {@link #inspect} is a plain
 * {@code GET}. Every Redis failure is mapped to an {@code UNAVAILABLE} result.
 *
 * <p>Thread-safe.
 */
final class LettuceRunLock implements RunLockPort {

    private final RedisConnector redis;
    private final String nodeId;
    private final SecureRandom random;

    LettuceRunLock(RedisConnector redis, String nodeId, SecureRandom random) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.nodeId = LockToken.requireNodeId(nodeId);
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public AcquireResult acquire(RunId runId, Fence fence, Duration ttl) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(fence, "fence");
        String ttlMillis = ttlMillis(ttl);
        RunLock lock = new RunLock(runId, LockToken.generate(fence, nodeId, random));
        AcquireResult result;
        try {
            long reply = redis.evalInteger(
                    LuaScript.ACQUIRE, lock.key(), lock.token().value(), ttlMillis);
            result = reply == 1 ? new AcquireResult.Acquired(lock) : new AcquireResult.Held();
        } catch (RedisUnavailableException e) {
            result = new AcquireResult.Unavailable();
        }
        redis.metrics().acquired(result.outcome());
        return result;
    }

    @Override
    public RemainingResult remaining(RunLock lock) {
        Objects.requireNonNull(lock, "lock");
        try {
            long millis = redis.evalInteger(
                    LuaScript.REMAINING, lock.key(), lock.token().value());
            // PTTL is -2 when the key is gone and -1 when it has no TTL; a lock is always written with one, so any
            // negative reply is treated as not owned (fail closed).
            return millis >= 0 ? new RemainingResult.Owned(Duration.ofMillis(millis)) : new RemainingResult.NotOwned();
        } catch (RedisUnavailableException e) {
            return new RemainingResult.Unavailable();
        }
    }

    @Override
    public ReleaseResult release(RunLock lock) {
        Objects.requireNonNull(lock, "lock");
        try {
            long deleted = redis.evalInteger(
                    LuaScript.RELEASE, lock.key(), lock.token().value());
            return deleted == 1 ? ReleaseResult.RELEASED : ReleaseResult.NOT_OWNED;
        } catch (RedisUnavailableException e) {
            return ReleaseResult.UNAVAILABLE;
        }
    }

    @Override
    public ExtendResult extend(RunLock lock, Duration ttl) {
        Objects.requireNonNull(lock, "lock");
        String ttlMillis = ttlMillis(ttl);
        try {
            long extended =
                    redis.evalInteger(LuaScript.EXTEND, lock.key(), lock.token().value(), ttlMillis);
            return extended == 1 ? ExtendResult.EXTENDED : ExtendResult.NOT_OWNED;
        } catch (RedisUnavailableException e) {
            return ExtendResult.UNAVAILABLE;
        }
    }

    @Override
    public LockInspection inspect(RunId runId) {
        String key = RunLock.key(Objects.requireNonNull(runId, "runId"));
        try {
            Optional<String> value = redis.call(commands -> Optional.ofNullable(commands.get(key)));
            return value.<LockInspection>map(LockInspection.Locked::new).orElse(new LockInspection.Free());
        } catch (RedisUnavailableException e) {
            return new LockInspection.Unavailable();
        }
    }

    private static String ttlMillis(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.toMillis() < 1 || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("A lock TTL is 1ms to " + MAX_TTL);
        }
        return Long.toString(ttl.toMillis());
    }
}
