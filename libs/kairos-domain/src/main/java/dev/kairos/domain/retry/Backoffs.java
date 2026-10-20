package dev.kairos.domain.retry;

import java.time.Duration;
import java.util.random.RandomGenerator;

/** Shared arithmetic of the {@link RetryBackoff} strategies, in milliseconds. */
final class Backoffs {

    private Backoffs() {}

    /**
     * Validates the arguments every strategy receives.
     *
     * @return {@code max} in milliseconds
     */
    static long checkArguments(int retry, Duration base, Duration max) {
        if (retry < 1) {
            throw new IllegalArgumentException("The retry number starts at 1");
        }
        if (base.isNegative() || base.isZero()) {
            throw new IllegalArgumentException("The base delay must be positive");
        }
        if (max.compareTo(base) < 0) {
            throw new IllegalArgumentException("The maximum delay must not be below the base delay");
        }
        return max.toMillis();
    }

    /**
     * Returns {@code min(maxMillis, base * 2^(retry - 1))} without overflowing.
     *
     * @param retry the retry number, at least 1
     * @param baseMillis the base delay in milliseconds, positive
     * @param maxMillis the cap in milliseconds, at least {@code baseMillis}
     */
    static long exponentialCeiling(int retry, long baseMillis, long maxMillis) {
        int shift = retry - 1;
        if (shift >= Long.SIZE - 1 || baseMillis > (maxMillis >> shift)) {
            return maxMillis;
        }
        return Math.min(maxMillis, baseMillis << shift);
    }

    /**
     * Returns a uniformly drawn part of {@code span}, in {@code [0, span)} (0 when the span is 0).
     *
     * @param span the span in milliseconds, not negative
     * @param random the generator; exactly one {@code nextDouble()} is drawn
     */
    static long jitter(long span, RandomGenerator random) {
        double fraction = random.nextDouble();
        return (long) Math.floor(fraction * span);
    }
}
