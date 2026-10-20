package dev.kairos.domain.retry;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with additive jitter: {@code min(max, base * 2^(n-1) + U[0, base/2))}.
 *
 * <p>With the defaults (30s base, 10-minute cap) the five retries wait about 30s, 60s, 120s,
 * 240s and 480s plus up to 15s each, so a run reaches the dead-letter queue roughly 15 to 17
 * minutes after its first failure. The jitter keeps runs that failed together from retrying in
 * lock-step against a recovering target.
 */
final class ExponentialJitterBackoff implements RetryBackoff {

    /** The only instance. */
    static final ExponentialJitterBackoff INSTANCE = new ExponentialJitterBackoff();

    private ExponentialJitterBackoff() {}

    @Override
    public Duration delay(int retry, Duration base, Duration max, RandomGenerator random) {
        long maxMillis = Backoffs.checkArguments(retry, base, max);
        long baseMillis = base.toMillis();
        long ceiling = Backoffs.exponentialCeiling(retry, baseMillis, maxMillis);
        long jitter = Backoffs.jitter(baseMillis / 2, random);
        return Duration.ofMillis(Math.min(maxMillis, ceiling + jitter));
    }

    @Override
    public Duration lowerBound(int retry, Duration base, Duration max) {
        long maxMillis = Backoffs.checkArguments(retry, base, max);
        return Duration.ofMillis(Backoffs.exponentialCeiling(retry, base.toMillis(), maxMillis));
    }

    @Override
    public Duration upperBound(int retry, Duration base, Duration max) {
        long maxMillis = Backoffs.checkArguments(retry, base, max);
        long baseMillis = base.toMillis();
        long ceiling = Backoffs.exponentialCeiling(retry, baseMillis, maxMillis);
        return Duration.ofMillis(Math.min(maxMillis, ceiling + baseMillis / 2));
    }
}
