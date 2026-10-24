package dev.kairos.watcher.delayed;

import dev.kairos.engine.lock.RedisConnector;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Reserves shared pacing windows in Redis while deferred runs remain durable in PostgreSQL. */
public final class DelayedPacer {
    private final RedisConnector redis;
    private final String prefix;

    /** Uses the production key namespace shared by all watcher instances. */
    public DelayedPacer(RedisConnector redis) {
        this(redis, "kairos:pace:delayed:");
    }

    /** Uses a supplied key namespace, useful for isolated integration tests. */
    public DelayedPacer(RedisConnector redis, String prefix) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        if (!prefix.startsWith("kairos:") || !prefix.endsWith(":")) {
            throw new IllegalArgumentException("Invalid Redis pacing key prefix");
        }
    }

    /** Returns the next durable due time if the shared one-second window is full, or null if reserved. */
    public @Nullable Instant acquire(DelayedRun delayed, int teamLimit, Instant now) {
        Objects.requireNonNull(delayed, "delayed");
        Objects.requireNonNull(now, "now");
        String key = prefix + now.getEpochSecond();
        boolean reserved = redis.reserveDelayedPace(
                key,
                delayed.run().teamId().value(),
                delayed.run().definitionId(),
                delayed.run().trigger(),
                teamLimit);
        return reserved ? null : now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
    }
}
