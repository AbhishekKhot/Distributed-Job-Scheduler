package dev.kairos.api.support.ratelimit;

import dev.kairos.api.support.problem.ApiException;

/** Consumes rate-limit tokens. Implementations fail closed. */
public interface RateLimiter {

    /**
     * Takes one token from the bucket of a subject.
     *
     * @param limit the limit
     * @param subject what is limited: an IP address, a normalised email, a user id or a team id. It is hashed before
     *     it is used in a Redis key, so no personal data is stored in Redis.
     * @throws ApiException 429 {@code rate-limited} with the time until a token is available, or 503
     *     {@code unavailable} when the limit cannot be checked
     */
    void consume(RateLimit limit, String subject);
}
