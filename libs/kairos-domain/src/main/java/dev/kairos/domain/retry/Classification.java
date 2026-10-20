package dev.kairos.domain.retry;

import dev.kairos.domain.model.ErrorClass;
import java.util.Objects;

/** The verdict on one attempt: success, or a failure with its error class and follow-up. */
public sealed interface Classification {

    /** The attempt succeeded. */
    record Success() implements Classification {}

    /**
     * The attempt failed.
     *
     * @param errorClass why, as recorded in {@code run_history.error_class}
     * @param kind whether it may be retried
     */
    record Failure(ErrorClass errorClass, FailureKind kind) implements Classification {

        /**
         * Validates the components.
         *
         * @throws NullPointerException if a component is null
         */
        public Failure {
            Objects.requireNonNull(errorClass, "errorClass");
            Objects.requireNonNull(kind, "kind");
        }
    }
}
