package dev.kairos.domain.retry;

import dev.kairos.domain.model.ErrorClass;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Decides whether a failed attempt is retried or dead-lettered.
 *
 * <p>Rules, in order:
 *
 * <ol>
 *   <li>A {@link FailureKind#NON_RETRYABLE} failure is dead-lettered with its error class.
 *   <li>An {@link FailureKind#UNKNOWN_OUTCOME} under {@link UnknownOutcomePolicy#REVIEW} is
 *       dead-lettered with reason {@code UNKNOWN_OUTCOME}; under
 *       {@link UnknownOutcomePolicy#RETRY_WITH_IDEMPOTENCY_KEY} it continues like a retryable
 *       failure.
 *   <li>Once the series has used {@code maxRetries} retries, the run is dead-lettered with the
 *       last error class.
 *   <li>The delay is the policy's backoff for the next retry number. A {@code Retry-After} hint
 *       from the target lengthens it, but never beyond the policy's cap.
 *   <li>If the retry would be due more than {@code maxAgeSec} after the series started, the run is
 *       dead-lettered with reason {@code MAX_AGE_EXCEEDED}.
 * </ol>
 *
 * <p>A retry keeps the run id, so it sends the same {@code Idempotency-Key} as every earlier
 * attempt. Thread-safe if the random supplier is, for example {@code ThreadLocalRandom::current}.
 */
public final class RetryPlanner {

    private final Clock clock;
    private final Supplier<? extends RandomGenerator> random;

    /**
     * Creates a planner.
     *
     * @param clock the time source for due times
     * @param random supplies the jitter source for each decision; jitter is not a security value
     */
    public RetryPlanner(Clock clock, Supplier<? extends RandomGenerator> random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Decides what happens after a failed attempt.
     *
     * @param failed the failed attempt
     * @param policy the resolved retry policy
     * @param unknownOutcomePolicy the endpoint's unknown-outcome policy
     * @return retry with a due time, or dead-letter with a reason
     */
    public RetryDecision plan(FailedAttempt failed, RetryPolicy policy, UnknownOutcomePolicy unknownOutcomePolicy) {
        Objects.requireNonNull(failed, "failed");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(unknownOutcomePolicy, "unknownOutcomePolicy");
        Classification.Failure failure = failed.failure();
        if (failure.kind() == FailureKind.NON_RETRYABLE) {
            return new RetryDecision.DeadLetter(failure.errorClass());
        }
        if (failure.kind() == FailureKind.UNKNOWN_OUTCOME && !unknownOutcomePolicy.retries()) {
            return new RetryDecision.DeadLetter(ErrorClass.UNKNOWN_OUTCOME);
        }
        if (!policy.allowsRetry(failed.retriesUsed())) {
            return new RetryDecision.DeadLetter(failure.errorClass());
        }
        Duration delay = policy.delayBefore(failed.retriesUsed() + 1, random.get());
        Duration retryAfter = failed.retryAfter();
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter.compareTo(policy.maxDelay()) > 0 ? policy.maxDelay() : retryAfter;
        }
        Instant dueAt = clock.instant().plus(delay);
        if (dueAt.isAfter(failed.seriesStartedAt().plus(policy.maxAge()))) {
            return new RetryDecision.DeadLetter(ErrorClass.MAX_AGE_EXCEEDED);
        }
        return new RetryDecision.Retry(failed.runId(), failed.attempt() + 1, delay, dueAt);
    }
}
