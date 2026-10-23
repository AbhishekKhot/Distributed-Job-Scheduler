package dev.kairos.api.team.application;

import java.util.Objects;

/**
 * The caller's team with its usage (LLD section 3.4, API 9).
 *
 * @param team the team
 * @param usage the current usage
 */
public record TeamDetails(Team team, TeamUsage usage) {

    /** Validates the components. */
    public TeamDetails {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(usage, "usage");
    }
}
