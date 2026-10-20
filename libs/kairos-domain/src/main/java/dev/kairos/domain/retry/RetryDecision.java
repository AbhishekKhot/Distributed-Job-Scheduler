package dev.kairos.domain.retry;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.model.ErrorClass;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** What the result settler does with a failed attempt: retry later, or dead-letter the run. */
public sealed interface RetryDecision {

    /**
     * Retry: the run becomes {@code DELAYED} until {@code dueAt}, then the delayed poller
     * dispatches attempt {@code nextAttempt}.
     *
     * @param idempotencyKey the {@code Idempotency-Key} the retry sends: the run id, the same as
     *     every earlier attempt, so the target can recognise a repeat
     * @param nextAttempt the attempt number of the retry
     * @param delay the backoff delay
     * @param dueAt when the retry is dispatched
     */
    record Retry(RunId idempotencyKey, int nextAttempt, Duration delay, Instant dueAt) implements RetryDecision {

        /**
         * Validates the components.
         *
         * @throws NullPointerException if a component is null
         * @throws IllegalArgumentException if {@code nextAttempt < 2} or the delay is negative
         */
        public Retry {
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(delay, "delay");
            Objects.requireNonNull(dueAt, "dueAt");
            if (nextAttempt < 2) {
                throw new IllegalArgumentException("A retry is at least the second attempt");
            }
            if (delay.isNegative()) {
                throw new IllegalArgumentException("A retry delay cannot be negative");
            }
        }
    }

    /**
     * Dead-letter: the run leaves {@code run_inflight} with outcome {@code DEAD_LETTERED} and a
     * {@code dead_letters} entry.
     *
     * @param reason the entry's reason ({@code dead_letters.reason})
     */
    record DeadLetter(ErrorClass reason) implements RetryDecision {

        /**
         * Validates the reason.
         *
         * @throws NullPointerException if the reason is null
         */
        public DeadLetter {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
