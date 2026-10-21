package dev.kairos.api.support.ratelimit.infrastructure;

/** Redis cannot be reached for rate limits right now; the request fails closed with 503. */
public final class RateLimitRedisUnavailableException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what failed
     */
    public RateLimitRedisUnavailableException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message what failed
     * @param cause the Redis failure
     */
    public RateLimitRedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
