package dev.kairos.api.support.problem;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.KairosException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A failure that only the API layer raises: authentication, team membership, API-key restrictions, rate limits and
 * unavailable dependencies (LLD section 3.1, errors common to every authenticated API).
 *
 * <p>The detail sentences are the LLD's. A 429 or 503 carries how long the client should wait, which becomes the
 * {@code Retry-After} header.
 */
public final class ApiException extends KairosException {

    /** LLD message of {@link ErrorCode#UNAUTHENTICATED}. */
    public static final String UNAUTHENTICATED_DETAIL = "A valid access token or API key is required.";

    /** LLD message of {@link ErrorCode#NO_TEAM}. */
    public static final String NO_TEAM_DETAIL = "Create a team or ask a member to add this user.";

    /** LLD message of {@link ErrorCode#FORBIDDEN_FOR_API_KEY}. */
    public static final String FORBIDDEN_FOR_API_KEY_DETAIL = "This operation requires a user access token.";

    /** LLD message of {@link ErrorCode#RATE_LIMITED}. */
    public static final String RATE_LIMITED_DETAIL = "Too many requests. Retry after the time in Retry-After.";

    /** LLD message of {@link ErrorCode#UNAVAILABLE}. */
    public static final String UNAVAILABLE_DETAIL =
            "The service is temporarily unavailable. Retry after the time in Retry-After.";

    /** LLD message of {@link ErrorCode#INTERNAL}. */
    public static final String INTERNAL_DETAIL = "An unexpected error occurred. Quote the traceId when reporting it.";

    private final @Nullable Duration retryAfter;

    /**
     * Creates an exception.
     *
     * @param errorCode the error code
     * @param detail the fixed, client-safe detail
     * @param retryAfter how long the client should wait, for 429 and 503; {@code null} otherwise
     */
    public ApiException(ErrorCode errorCode, String detail, @Nullable Duration retryAfter) {
        super(errorCode, detail, Set.of(errorCode.status()));
        this.retryAfter = retryAfter;
    }

    /**
     * Returns 401 {@code unauthenticated}.
     *
     * @return the exception
     */
    public static ApiException unauthenticated() {
        return new ApiException(ErrorCode.UNAUTHENTICATED, UNAUTHENTICATED_DETAIL, null);
    }

    /**
     * Returns 403 {@code no-team}, for a user without a team on a team-scoped operation.
     *
     * @return the exception
     */
    public static ApiException noTeam() {
        return new ApiException(ErrorCode.NO_TEAM, NO_TEAM_DETAIL, null);
    }

    /**
     * Returns 403 {@code forbidden-for-api-key}, for an API key on an operation reserved for user tokens.
     *
     * @return the exception
     */
    public static ApiException forbiddenForApiKey() {
        return new ApiException(ErrorCode.FORBIDDEN_FOR_API_KEY, FORBIDDEN_FOR_API_KEY_DETAIL, null);
    }

    /**
     * Returns 429 {@code rate-limited}.
     *
     * @param retryAfter when the next request may succeed
     * @return the exception
     */
    public static ApiException rateLimited(Duration retryAfter) {
        return new ApiException(ErrorCode.RATE_LIMITED, RATE_LIMITED_DETAIL, retryAfter);
    }

    /**
     * Returns 503 {@code unavailable}, for a dependency (Redis, a database) that cannot be reached.
     *
     * @param retryAfter when to retry
     * @return the exception
     */
    public static ApiException unavailable(Duration retryAfter) {
        return new ApiException(ErrorCode.UNAVAILABLE, UNAVAILABLE_DETAIL, retryAfter);
    }

    /**
     * Returns how long the client should wait before retrying.
     *
     * @return the wait, if the problem has one
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
