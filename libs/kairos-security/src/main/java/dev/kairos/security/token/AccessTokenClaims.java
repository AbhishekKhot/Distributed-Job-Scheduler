package dev.kairos.security.token;

import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The verified claims of an access token.
 *
 * @param user the user ({@code sub})
 * @param team the user's team ({@code team}), or {@code null} for a user without a team (who gets 403 {@code no-team}
 *     on scheduling APIs)
 * @param sessionId the refresh-token session the token was issued for ({@code sid})
 * @param tokenId the unique token id ({@code jti})
 * @param issuedAt the issue time ({@code iat})
 * @param expiresAt the expiry time ({@code exp})
 */
public record AccessTokenClaims(
        UserId user, @Nullable TeamId team, UUID sessionId, String tokenId, Instant issuedAt, Instant expiresAt) {

    /** Validates the components. */
    public AccessTokenClaims {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /**
     * Returns the team, if the user has one.
     *
     * @return the team
     */
    public Optional<TeamId> teamId() {
        return Optional.ofNullable(team);
    }
}
