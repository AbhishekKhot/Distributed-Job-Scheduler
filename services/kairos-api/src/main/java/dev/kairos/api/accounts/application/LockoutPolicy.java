package dev.kairos.api.accounts.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Progressive login lockout (LLD section 3.3, API 2: repeated failures lock the account for a growing period).
 *
 * <p>Once {@code threshold} consecutive failures are reached, each further failure locks the account for
 * {@code base * 2^(failures - threshold)}, capped at {@code max}. Attempts made while the account is locked are refused
 * without being counted, so a locked account allows at most one guess per lock period. A successful login resets the
 * count.
 *
 * @param threshold the consecutive failures that start a lock
 * @param base the first lock period
 * @param max the longest lock period
 */
public record LockoutPolicy(int threshold, Duration base, Duration max) {

    // 2^20 times any sensible base is far beyond any sensible max; the cap keeps the shift from overflowing.
    private static final int MAX_DOUBLINGS = 20;

    /**
     * Validates the policy.
     *
     * @throws IllegalArgumentException if the threshold is not positive or the periods are not positive and ordered
     */
    public LockoutPolicy {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(max, "max");
        if (threshold < 1) {
            throw new IllegalArgumentException("the lockout threshold must be positive");
        }
        if (base.isNegative() || base.isZero() || max.compareTo(base) < 0) {
            throw new IllegalArgumentException("the lockout periods must be positive and base must not exceed max");
        }
    }

    /**
     * Returns until when an account is locked after a failure.
     *
     * @param failures the consecutive failures, including this one
     * @param now the time of the failure
     * @return the end of the lock, or empty if the account stays unlocked
     */
    public Optional<Instant> lockedUntil(int failures, Instant now) {
        if (failures < threshold) {
            return Optional.empty();
        }
        int doublings = Math.min(failures - threshold, MAX_DOUBLINGS);
        Duration period = base.multipliedBy(1L << doublings);
        return Optional.of(now.plus(period.compareTo(max) > 0 ? max : period));
    }
}
