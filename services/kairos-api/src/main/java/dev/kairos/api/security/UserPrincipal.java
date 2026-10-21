package dev.kairos.api.security;

import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A user authenticated with an access token.
 *
 * @param user the user ({@code sub})
 * @param teamId the user's team ({@code team}), or {@code null} for a user without a team
 * @param sessionId the login session the token belongs to ({@code sid}, the refresh-token family)
 */
public record UserPrincipal(UserId user, @Nullable TeamId teamId, UUID sessionId) implements KairosPrincipal {

    /** Validates the components. */
    public UserPrincipal {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(sessionId, "sessionId");
    }

    @Override
    public Optional<TeamId> team() {
        return Optional.ofNullable(teamId);
    }

    @Override
    public String actor() {
        return "user:" + user.value();
    }
}
