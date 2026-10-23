package dev.kairos.api.team.application;

import dev.kairos.domain.id.TeamId;
import java.time.Instant;
import java.util.Objects;

/**
 * A team (LLD section 3.4, APIs 8 and 9).
 *
 * @param id the team id
 * @param name the team's slug
 * @param quotas the effective quotas
 * @param createdAt when the team was created
 */
public record Team(TeamId id, String name, TeamQuotas quotas, Instant createdAt) {

    /** Validates the components. */
    public Team {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(quotas, "quotas");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
