package dev.kairos.domain.retry;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Fixed backoff: every retry waits {@code base}.
 *
 * <p>No jitter, so the delay is exactly predictable and the random source is not used. Meant
 * for targets with a known recovery time and low fan-in; prefer a jittered strategy when many runs
 * share a target.
 */
final class FixedBackoff implements RetryBackoff {

    /** The only instance. */
    static final FixedBackoff INSTANCE = new FixedBackoff();

    private FixedBackoff() {}

    @Override
    public Duration delay(int retry, Duration base, Duration max, RandomGenerator random) {
        Backoffs.checkArguments(retry, base, max);
        return base;
    }

    @Override
    public Duration lowerBound(int retry, Duration base, Duration max) {
        Backoffs.checkArguments(retry, base, max);
        return base;
    }

    @Override
    public Duration upperBound(int retry, Duration base, Duration max) {
        Backoffs.checkArguments(retry, base, max);
        return base;
    }
}
