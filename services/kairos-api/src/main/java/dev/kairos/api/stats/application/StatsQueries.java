package dev.kairos.api.stats.application;

import dev.kairos.domain.id.TeamId;
import java.time.Duration;

/** Reads team counters and latency percentiles. */
public interface StatsQueries {
    /** Returns statistics for the trailing window. */
    TeamStats get(TeamId team, String window, Duration duration);
}
