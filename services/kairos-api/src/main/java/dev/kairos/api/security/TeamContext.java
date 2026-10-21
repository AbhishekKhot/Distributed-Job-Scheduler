package dev.kairos.api.security;

import dev.kairos.domain.id.TeamId;
import java.util.Objects;

/**
 * The caller's team on a team-scoped operation. A controller method that declares a {@code TeamContext} parameter
 * receives it from the verified credential; a user without a team gets 403 {@code no-team} before the method runs.
 *
 * @param teamId the caller's team; every query on team data filters on it
 * @param principal the caller, for audit entries
 */
public record TeamContext(TeamId teamId, KairosPrincipal principal) {

    /** Validates the components. */
    public TeamContext {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(principal, "principal");
    }

    /**
     * Returns the actor recorded in the audit log.
     *
     * @return {@code user:{id}} or {@code key:{keyId}}
     */
    public String actor() {
        return principal.actor();
    }
}
