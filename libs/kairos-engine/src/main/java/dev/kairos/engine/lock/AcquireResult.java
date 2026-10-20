package dev.kairos.engine.lock;

import java.util.Locale;
import java.util.Objects;

/**
 * The result of {@link RunLockPort#acquire}: {@link Acquired}, {@link Held} by another owner, or {@link Unavailable}
 * because Redis failed or timed out. Only {@link Acquired} lets the caller go on to the next gate.
 */
public sealed interface AcquireResult permits AcquireResult.Acquired, AcquireResult.Held, AcquireResult.Unavailable {

    /**
     * Returns the outcome, for metrics and logs.
     *
     * @return the outcome
     */
    Outcome outcome();

    /** The outcome of an acquisition. */
    enum Outcome {
        /** This caller now owns the lock. */
        ACQUIRED,
        /** Another owner holds the lock. */
        HELD,
        /** Redis failed or timed out. */
        UNAVAILABLE;

        /**
         * Returns the lower-case name used as the {@code outcome} metric label.
         *
         * @return for example {@code acquired}
         */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * This caller owns the lock until it expires, is released, or is lost in a Redis failover.
     *
     * @param lock the handle for the other lock operations
     */
    record Acquired(RunLock lock) implements AcquireResult {

        /** Validates the handle. */
        public Acquired {
            Objects.requireNonNull(lock, "lock");
        }

        @Override
        public Outcome outcome() {
            return Outcome.ACQUIRED;
        }
    }

    /** Another owner holds the lock: the run is already being executed, or a duplicate message arrived. */
    record Held() implements AcquireResult {
        @Override
        public Outcome outcome() {
            return Outcome.HELD;
        }
    }

    /**
     * Redis failed, rejected the command or did not answer within the command timeout. The lock may or may not have
     * been taken (a timed-out {@code SET} can still apply); either way it expires with its TTL, and the run is
     * recovered by the usual paths.
     */
    record Unavailable() implements AcquireResult {
        @Override
        public Outcome outcome() {
            return Outcome.UNAVAILABLE;
        }
    }
}
