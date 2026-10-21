package dev.kairos.api.support.ratelimit.infrastructure;

import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Readiness of the rate-limit Redis (IMPLEMENTATION_CHECKLIST section 4.9): every limit fails closed without it, so
 * the API is not ready while Redis does not answer a {@code PING}. Reports no connection details.
 */
public final class RateLimitRedisHealthIndicator implements HealthIndicator {

    /** The name of the health contributor. */
    public static final String NAME = "redis";

    private final RateLimitRedis redis;

    /**
     * Creates the indicator.
     *
     * @param redis the Redis connection
     */
    public RateLimitRedisHealthIndicator(RateLimitRedis redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public Health health() {
        return redis.ping() ? Health.up().build() : Health.down().build();
    }
}
