package dev.kairos.engine.lock;

import java.util.Objects;
import java.util.Optional;

/**
 * The result of {@link RunLockPort#inspect}: who, if anyone, holds a run's lock. The lease sweeper uses it to tell a
 * live executor from a dead one before it recovers a run.
 */
public sealed interface LockInspection permits LockInspection.Locked, LockInspection.Free, LockInspection.Unavailable {

    /**
     * The lock is held.
     *
     * @param value the stored owner token; a value that is not a well-formed token still means the lock is held
     */
    record Locked(String value) implements LockInspection {

        /** Validates the value. */
        public Locked {
            Objects.requireNonNull(value, "value");
        }

        /**
         * Returns the owner's token.
         *
         * @return the parsed token, or empty if the stored value is not a well-formed token
         */
        public Optional<LockToken> token() {
            return LockToken.parse(value);
        }
    }

    /** Nobody holds the lock: it was never taken, expired or was released. */
    record Free() implements LockInspection {}

    /** Redis failed, rejected the command or did not answer within the command timeout. */
    record Unavailable() implements LockInspection {}
}
