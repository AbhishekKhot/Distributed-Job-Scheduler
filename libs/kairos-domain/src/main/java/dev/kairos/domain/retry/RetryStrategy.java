package dev.kairos.domain.retry;

/**
 * Backoff strategy named in a retry policy ({@code retryPolicy.strategy} in endpoint and job
 * specs). Stored by name in JSON, so constants are never renamed.
 */
public enum RetryStrategy {
    /** {@code min(max, base * 2^(n-1) + U[0, base/2))}; the default. */
    EXPONENTIAL_JITTER,
    /** {@code U[0, min(max, base * 2^(n-1)))}. */
    FULL_JITTER,
    /** {@code base} for every retry, without jitter. */
    FIXED;

    /**
     * Returns the backoff implementation.
     *
     * @return a stateless, thread-safe backoff
     */
    public RetryBackoff backoff() {
        return switch (this) {
            case EXPONENTIAL_JITTER -> ExponentialJitterBackoff.INSTANCE;
            case FULL_JITTER -> FullJitterBackoff.INSTANCE;
            case FIXED -> FixedBackoff.INSTANCE;
        };
    }
}
