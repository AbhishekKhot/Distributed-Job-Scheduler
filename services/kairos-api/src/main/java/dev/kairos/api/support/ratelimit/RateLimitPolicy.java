package dev.kairos.api.support.ratelimit;

import java.time.Duration;
import java.util.Objects;

/**
 * A token bucket: up to {@code capacity} requests, refilled evenly over {@code period}.
 *
 * @param capacity the burst size and the number of requests per period
 * @param period the refill period
 */
public record RateLimitPolicy(long capacity, Duration period) {

    /**
     * Validates the policy.
     *
     * @throws IllegalArgumentException if the capacity or period is out of range
     */
    public RateLimitPolicy {
        Objects.requireNonNull(period, "period");
        if (capacity < 1 || capacity > 1_000_000) {
            throw new IllegalArgumentException("capacity must be between 1 and 1000000");
        }
        if (period.compareTo(Duration.ofSeconds(1)) < 0 || period.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("period must be between 1s and 1d");
        }
    }
}
