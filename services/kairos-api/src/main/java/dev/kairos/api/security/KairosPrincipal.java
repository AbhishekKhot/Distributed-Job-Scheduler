package dev.kairos.api.security;

import dev.kairos.domain.id.TeamId;
import java.util.Optional;

/**
 * The authenticated caller of a request: a user with an access token, or a team API key.
 *
 * <p>The team is taken from the verified credential only. A user may have no team yet; an API key always belongs to
 * one.
 */
public sealed interface KairosPrincipal permits UserPrincipal, ApiKeyPrincipal {

    /**
     * Returns the caller's team.
     *
     * @return the team, or empty for a user who has none yet
     */
    Optional<TeamId> team();

    /**
     * Returns the actor recorded in the audit log, {@code user:{id}} or {@code key:{keyId}} (LLD section 2.2).
     *
     * @return the actor
     */
    String actor();
}
