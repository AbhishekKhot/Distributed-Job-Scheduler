package dev.kairos.domain.health;

import java.time.Instant;
import java.util.Objects;

/** Result of evaluating an endpoint against the {@link EndpointSuspendRule}. */
public sealed interface SuspendDecision {

    /** Why an endpoint is suspended. */
    enum Reason {
        /** Too many dead letters in the window. */
        DEAD_LETTERS,
        /** Too high a failure ratio over enough attempts. */
        FAILURE_RATE
    }

    /** The endpoint is healthy enough; nothing to do. */
    record Keep() implements SuspendDecision {}

    /**
     * Suspend the endpoint (status {@code SUSPENDED}, reason {@code AUTO_SUSPENDED}).
     *
     * @param reason which threshold tripped; dead letters win when both do
     */
    record Suspend(Reason reason) implements SuspendDecision {

        /**
         * Validates the reason.
         *
         * @throws NullPointerException if the reason is null
         */
        public Suspend {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * A threshold tripped, but the endpoint changed status too recently to be suspended again.
     *
     * @param reason which threshold tripped
     * @param until when the cool-down ends
     */
    record CoolingDown(Reason reason, Instant until) implements SuspendDecision {

        /**
         * Validates the components.
         *
         * @throws NullPointerException if a component is null
         */
        public CoolingDown {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(until, "until");
        }
    }
}
