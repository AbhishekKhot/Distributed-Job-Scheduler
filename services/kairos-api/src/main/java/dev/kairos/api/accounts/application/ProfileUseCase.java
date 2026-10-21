package dev.kairos.api.accounts.application;

import dev.kairos.api.support.problem.ApiException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.id.UserId;
import java.util.Objects;

/**
 * API 5, {@code GET /v1/me} (LLD section 3.3): the calling user's profile with the current team, which may be empty.
 * Thread-safe.
 */
public final class ProfileUseCase {

    /** LLD message of the 401 of the profile operations: they need a user access token, not an API key. */
    public static final String ACCESS_TOKEN_REQUIRED_DETAIL = "A valid access token is required.";

    private final UserAccounts users;

    /**
     * Creates the use case.
     *
     * @param users the user store
     */
    public ProfileUseCase(UserAccounts users) {
        this.users = Objects.requireNonNull(users, "users");
    }

    /**
     * Reads a profile.
     *
     * @param user the calling user
     * @return the profile
     * @throws ApiException 401 {@code unauthenticated} if the user no longer exists
     */
    public UserProfile profile(UserId user) {
        return users.findProfile(user).orElseThrow(ProfileUseCase::accessTokenRequired);
    }

    /**
     * Returns the 401 of the profile operations.
     *
     * @return the exception
     */
    public static ApiException accessTokenRequired() {
        return new ApiException(ErrorCode.UNAUTHENTICATED, ACCESS_TOKEN_REQUIRED_DETAIL, null);
    }
}
