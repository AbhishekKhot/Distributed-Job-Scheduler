package dev.kairos.api.accounts.application;

import dev.kairos.security.token.RefreshTokenService;
import java.util.Objects;

/**
 * API 4, {@code POST /v1/auth/logout} (LLD section 3.3): revokes the login session (the refresh-token family) of a
 * token. Unknown, malformed and already revoked tokens are ignored, so the answer is always 204 and reveals nothing.
 * Access tokens of the session stop working within the session-check cache lifetime (30s at most). Thread-safe.
 */
public final class LogoutUseCase {

    private final RefreshTokenService refreshTokens;

    /**
     * Creates the use case.
     *
     * @param refreshTokens revokes refresh-token families
     */
    public LogoutUseCase(RefreshTokenService refreshTokens) {
        this.refreshTokens = Objects.requireNonNull(refreshTokens, "refreshTokens");
    }

    /**
     * Logs out.
     *
     * @param presented the refresh token from the request
     */
    public void logOut(String presented) {
        refreshTokens.revoke(presented);
    }
}
