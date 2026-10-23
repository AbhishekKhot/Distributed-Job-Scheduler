package dev.kairos.api.team.application;

import dev.kairos.domain.id.TeamId;
import java.time.Instant;

/** Reads what a team uses now from every cell database (LLD section 3.4, API 9). */
public interface TeamUsageReader {

    /**
     * Reads a team's usage.
     *
     * @param team the team
     * @param now the current time; execution rates are taken from the last complete minute before it
     * @return the usage summed over every cell
     */
    TeamUsage usage(TeamId team, Instant now);
}
