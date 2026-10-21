package dev.kairos.api.accounts.application;

import dev.kairos.api.support.problem.ApiException;
import dev.kairos.api.support.ratelimit.RateLimit;
import dev.kairos.api.support.ratelimit.RateLimiter;
import dev.kairos.domain.error.ErrorCode;
import java.util.Objects;

/**
 * Applies the accounts rate limits with the 429 messages of LLD section 3.3. Redis failures stay 503
 * {@code unavailable} (fail closed). Thread-safe.
 */
public final class AccountRateLimits {

    /** LLD message of 429 on signup. */
    public static final String SIGNUP_DETAIL = "Too many signups from this address. Retry later.";

    /** LLD message of 429 on login. */
    public static final String LOGIN_DETAIL = "Too many login attempts. Retry later.";

    /** LLD message of 429 on password change. */
    public static final String PASSWORD_CHANGE_DETAIL = "Too many attempts. Retry later.";

    private final RateLimiter limiter;

    /**
     * Creates the helper.
     *
     * @param limiter the rate limiter
     */
    public AccountRateLimits(RateLimiter limiter) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    /**
     * Takes one token, replacing the generic 429 detail with the operation's own.
     *
     * @param limit the limit
     * @param subject the limited subject; hashed by the limiter
     * @param detail the LLD message of the operation's 429
     * @throws ApiException 429 {@code rate-limited} with {@code detail}, or 503 {@code unavailable}
     */
    public void consume(RateLimit limit, String subject, String detail) {
        try {
            limiter.consume(limit, subject);
        } catch (ApiException e) {
            if (e.errorCode() != ErrorCode.RATE_LIMITED) {
                throw e;
            }
            throw new ApiException(
                    ErrorCode.RATE_LIMITED, detail, e.retryAfter().orElse(null));
        }
    }
}
