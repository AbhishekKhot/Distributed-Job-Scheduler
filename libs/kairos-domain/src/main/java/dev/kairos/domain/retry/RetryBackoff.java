package dev.kairos.domain.retry;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Turns a retry number into the delay before that retry (strategy pattern).
 *
 * <p>Contract, shared by every implementation and checked by one contract test:
 *
 * <ul>
 *   <li>{@code lowerBound(n) <= delay(n) <= upperBound(n) <= max} for every random draw;
 *   <li>both bounds are non-decreasing in {@code n};
 *   <li>for the same random draw, {@code delay(n + 1) >= delay(n)}, so the expected delay never
 *       decreases from one retry to the next;
 *   <li>the only randomness used is at most one {@link RandomGenerator#nextDouble()} call per
 *       delay, so a seeded or stubbed generator makes delays reproducible.
 * </ul>
 *
 * <p>Implementations are stateless and thread-safe. Jitter only spreads load; it is not a
 * security value, so it does not need a CSPRNG.
 */
public interface RetryBackoff {

    /**
     * Returns the delay before a retry.
     *
     * @param retry the retry number within the series, 1 for the first retry
     * @param base the base delay, positive
     * @param max the cap, at least {@code base}
     * @param random the source of the jitter
     * @return the delay, between {@link #lowerBound} and {@link #upperBound}
     * @throws IllegalArgumentException if {@code retry < 1}, {@code base} is not positive or
     *     {@code max < base}
     */
    Duration delay(int retry, Duration base, Duration max, RandomGenerator random);

    /**
     * Returns the smallest delay {@link #delay} can return for this retry.
     *
     * @param retry the retry number, at least 1
     * @param base the base delay, positive
     * @param max the cap, at least {@code base}
     * @return the lower bound
     * @throws IllegalArgumentException on the same arguments as {@link #delay}
     */
    Duration lowerBound(int retry, Duration base, Duration max);

    /**
     * Returns the largest delay {@link #delay} can return for this retry.
     *
     * @param retry the retry number, at least 1
     * @param base the base delay, positive
     * @param max the cap, at least {@code base}
     * @return the upper bound, at most {@code max}
     * @throws IllegalArgumentException on the same arguments as {@link #delay}
     */
    Duration upperBound(int retry, Duration base, Duration max);
}
