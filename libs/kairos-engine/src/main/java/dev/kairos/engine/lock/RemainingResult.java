package dev.kairos.engine.lock;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of {@link RunLockPort#remaining}: the caller still {@link Owned owns} the lock with some time left, does
 * {@link NotOwned not own} it any more (expired, released, taken over or lost in a failover), or Redis is
 * {@link Unavailable}. The send-time guard treats everything but {@link Owned} as a reason not to send.
 */
public sealed interface RemainingResult
        permits RemainingResult.Owned, RemainingResult.NotOwned, RemainingResult.Unavailable {

    /**
     * Returns the time left, only if the caller still owns the lock.
     *
     * @return the time left, or empty
     */
    default Optional<Duration> timeLeft() {
        return this instanceof Owned(Duration left) ? Optional.of(left) : Optional.empty();
    }

    /**
     * The caller owns the lock.
     *
     * @param left the lock's remaining time to live, not negative
     */
    record Owned(Duration left) implements RemainingResult {

        /**
         * Validates the time left.
         *
         * @throws IllegalArgumentException if it is negative
         */
        public Owned {
            Objects.requireNonNull(left, "left");
            if (left.isNegative()) {
                throw new IllegalArgumentException("The time left cannot be negative");
            }
        }
    }

    /** The lock expired, was released or is held by another owner. */
    record NotOwned() implements RemainingResult {}

    /** Redis failed, rejected the command or did not answer within the command timeout. */
    record Unavailable() implements RemainingResult {}
}
