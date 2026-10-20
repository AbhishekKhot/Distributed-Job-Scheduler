package dev.kairos.domain.retry;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Full jitter: {@code U[0, min(max, base * 2^(n-1)))}.
 *
 * <p>Spreads retries the most, at the cost of an occasional near-immediate retry. Suits targets
 * that many runs fail against at once, where de-synchronising the retries matters more than a
 * guaranteed minimum wait.
 */
final class FullJitterBackoff implements RetryBackoff {

    /** The only instance. */
    static final FullJitterBackoff INSTANCE = new FullJitterBackoff();

    private FullJitterBackoff() {}

    @Override
    public Duration delay(int retry, Duration base, Duration max, RandomGenerator random) {
        long maxMillis = Backoffs.checkArguments(retry, base, max);
        long ceiling = Backoffs.exponentialCeiling(retry, base.toMillis(), maxMillis);
        return Duration.ofMillis(Backoffs.jitter(ceiling, random));
    }

    @Override
    public Duration lowerBound(int retry, Duration base, Duration max) {
        Backoffs.checkArguments(retry, base, max);
        return Duration.ZERO;
    }

    @Override
    public Duration upperBound(int retry, Duration base, Duration max) {
        long maxMillis = Backoffs.checkArguments(retry, base, max);
        return Duration.ofMillis(Backoffs.exponentialCeiling(retry, base.toMillis(), maxMillis));
    }
}
