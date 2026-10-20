package dev.kairos.engine.lock;

import io.lettuce.core.SetArgs;
import java.util.Objects;

/**
 * {@link SentMarkerPort} on Lettuce: {@code SET key 1 PX 172800000} to mark, {@code EXISTS key} to check. Every Redis
 * failure is mapped to {@code UNAVAILABLE}.
 *
 * <p>Thread-safe.
 */
final class LettuceSentMarkers implements SentMarkerPort {

    private static final String VALUE = "1";
    private static final String OK = "OK";

    private final RedisConnector redis;

    LettuceSentMarkers(RedisConnector redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public MarkResult mark(SentMarker marker) {
        String key = Objects.requireNonNull(marker, "marker").key();
        SetArgs args = SetArgs.Builder.px(TTL.toMillis());
        try {
            String reply = redis.call(commands -> commands.set(key, VALUE, args));
            return OK.equals(reply) ? MarkResult.MARKED : MarkResult.UNAVAILABLE;
        } catch (RedisUnavailableException e) {
            return MarkResult.UNAVAILABLE;
        }
    }

    @Override
    public MarkerCheck check(SentMarker marker) {
        String key = Objects.requireNonNull(marker, "marker").key();
        try {
            Long count = redis.call(commands -> commands.exists(key));
            return count != null && count > 0 ? MarkerCheck.PRESENT : MarkerCheck.ABSENT;
        } catch (RedisUnavailableException e) {
            return MarkerCheck.UNAVAILABLE;
        }
    }
}
