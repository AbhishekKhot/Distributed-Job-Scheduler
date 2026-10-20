package dev.kairos.domain.retry;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Retry limits of a run ({@code retryPolicy} in endpoint and job specs, {@code teams.retry_default}).
 *
 * <p>A retry series is the attempts since the run was created or last redriven. It allows
 * {@code maxRetries} retries after the first attempt, and no retry is scheduled whose due time
 * would be more than {@code maxAgeSec} after the series started. Policies replace each other as a
 * whole (see {@link #resolve}); fields are never merged.
 *
 * <p>Ranges beyond the LLD defaults: at most 20 retries, base delay 1s to 1h, cap from the base
 * to 24h, and maximum age 60s to 7 days (the retention of run history, so every attempt of a
 * series stays visible).
 *
 * @param maxRetries retries after the first attempt, 0 to 20
 * @param strategy the backoff strategy
 * @param baseDelaySec the base delay in seconds, 1 to 3600
 * @param maxDelaySec the cap on one delay in seconds, {@code baseDelaySec} to 86400
 * @param maxAgeSec the longest a series may last in seconds, 60 to 604800
 */
public record RetryPolicy(int maxRetries, RetryStrategy strategy, int baseDelaySec, int maxDelaySec, int maxAgeSec) {

    /** Most retries a policy may allow. */
    public static final int MAX_RETRIES = 20;

    /** Largest base delay, in seconds. */
    public static final int MAX_BASE_DELAY_SEC = 3_600;

    /** Largest cap on one delay, in seconds. */
    public static final int MAX_MAX_DELAY_SEC = 86_400;

    /** Shortest maximum series age, in seconds. */
    public static final int MIN_MAX_AGE_SEC = 60;

    /** Longest maximum series age, in seconds (the 7-day run history retention). */
    public static final int MAX_MAX_AGE_SEC = 604_800;

    /**
     * The platform default (LLD section 4.1, result settler): 5 retries, exponential backoff with
     * jitter, 30s base, 10-minute cap and 24h maximum age, about 15 minutes to the dead-letter
     * queue.
     */
    public static final RetryPolicy PLATFORM_DEFAULT =
            new RetryPolicy(5, RetryStrategy.EXPONENTIAL_JITTER, 30, 600, 86_400);

    private static final String FIELD = "retryPolicy.";

    /**
     * Validates the components.
     *
     * @throws ValidationFailedException with code {@link FieldError#RANGE} on the first field
     *     outside its range ({@code retryPolicy.maxRetries} and so on)
     * @throws NullPointerException if {@code strategy} is null
     */
    public RetryPolicy {
        Objects.requireNonNull(strategy, "strategy");
        requireRange("maxRetries", maxRetries, 0, MAX_RETRIES);
        requireRange("baseDelaySec", baseDelaySec, 1, MAX_BASE_DELAY_SEC);
        requireRange("maxDelaySec", maxDelaySec, baseDelaySec, MAX_MAX_DELAY_SEC);
        requireRange("maxAgeSec", maxAgeSec, MIN_MAX_AGE_SEC, MAX_MAX_AGE_SEC);
    }

    private static void requireRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw ValidationFailedException.of(FIELD + field, FieldError.RANGE);
        }
    }

    /**
     * Picks the policy that applies to a run: the job's override, else the endpoint's, else the
     * team default, else the platform default.
     *
     * @param job the job's {@code retryPolicy}, or null if not set (always null for workflow tasks)
     * @param endpoint the endpoint's {@code retryPolicy}, or null if not set
     * @param team the team's {@code retry_default}, or null if not set
     * @param platform the platform default, normally {@link #PLATFORM_DEFAULT} from configuration
     * @return the first non-null policy in that order
     */
    public static RetryPolicy resolve(
            @Nullable RetryPolicy job,
            @Nullable RetryPolicy endpoint,
            @Nullable RetryPolicy team,
            RetryPolicy platform) {
        Objects.requireNonNull(platform, "platform");
        if (job != null) {
            return job;
        }
        if (endpoint != null) {
            return endpoint;
        }
        return team != null ? team : platform;
    }

    /**
     * Returns the base delay.
     *
     * @return {@code baseDelaySec} as a duration
     */
    public Duration baseDelay() {
        return Duration.ofSeconds(baseDelaySec);
    }

    /**
     * Returns the cap on one delay.
     *
     * @return {@code maxDelaySec} as a duration
     */
    public Duration maxDelay() {
        return Duration.ofSeconds(maxDelaySec);
    }

    /**
     * Returns the longest a retry series may last.
     *
     * @return {@code maxAgeSec} as a duration
     */
    public Duration maxAge() {
        return Duration.ofSeconds(maxAgeSec);
    }

    /**
     * Tells whether another retry is allowed after the given number of retries.
     *
     * @param retriesUsed retries already made in the series, not negative
     * @return true while {@code retriesUsed < maxRetries}
     * @throws IllegalArgumentException if {@code retriesUsed} is negative
     */
    public boolean allowsRetry(int retriesUsed) {
        if (retriesUsed < 0) {
            throw new IllegalArgumentException("The number of retries cannot be negative");
        }
        return retriesUsed < maxRetries;
    }

    /**
     * Returns the delay before a retry, using this policy's strategy, base and cap.
     *
     * @param retry the retry number within the series, 1 for the first retry
     * @param random the source of the jitter
     * @return the delay, at most {@link #maxDelay()}
     * @throws IllegalArgumentException if {@code retry < 1}
     */
    public Duration delayBefore(int retry, RandomGenerator random) {
        return strategy.backoff().delay(retry, baseDelay(), maxDelay(), random);
    }
}
