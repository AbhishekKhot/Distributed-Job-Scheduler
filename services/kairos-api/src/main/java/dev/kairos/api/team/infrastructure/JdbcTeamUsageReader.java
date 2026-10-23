package dev.kairos.api.team.infrastructure;

import dev.kairos.api.team.application.TeamUsage;
import dev.kairos.api.team.application.TeamUsageReader;
import dev.kairos.domain.id.TeamId;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * {@link TeamUsageReader} on the cell databases. A team's jobs and runs are spread over every cell (the shard depends
 * on the name), so each cell is asked once, with one statement that reads three counters:
 *
 * <ul>
 *   <li>{@code jobs}: the team's jobs that are not {@code DELETED} ({@code jobs_team_created_ix});
 *   <li>{@code inflight}: the team's runs in state {@code RUNNING} ({@code run_inflight_team_scheduled_ix});
 *   <li>{@code execPerSec}: the attempts of the last complete minute in {@code team_stats_minute}, over every node,
 *       divided by 60 and rounded.
 * </ul>
 *
 * The counters are summed over the cells. Thread-safe.
 */
public final class JdbcTeamUsageReader implements TeamUsageReader {

    private static final String USAGE = """
            SELECT (SELECT count(*)
                      FROM scheduler.jobs
                     WHERE team_id = :teamId AND status <> 'DELETED') AS jobs,
                   (SELECT count(*)
                      FROM scheduler.run_inflight
                     WHERE team_id = :teamId AND state = 3) AS inflight,
                   (SELECT COALESCE(sum(attempts), 0)
                      FROM scheduler.team_stats_minute
                     WHERE team_id = :teamId AND minute = :minute) AS attempts
            """;

    private static final long SECONDS_PER_MINUTE = 60;

    private final CellRouter cells;

    /**
     * Creates the reader.
     *
     * @param cells the cell router
     */
    public JdbcTeamUsageReader(CellRouter cells) {
        this.cells = Objects.requireNonNull(cells, "cells");
    }

    @Override
    public TeamUsage usage(TeamId team, Instant now) {
        Instant minute = now.truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(1));
        long jobs = 0;
        long inflight = 0;
        long attempts = 0;
        for (Cell cell : cells.allCells()) {
            Counters counters = cell.jdbc()
                    .sql(USAGE)
                    .param("teamId", team.value())
                    .param("minute", JdbcTeamStore.utc(minute))
                    .query((rs, row) ->
                            new Counters(rs.getLong("jobs"), rs.getLong("inflight"), rs.getLong("attempts")))
                    .single();
            jobs += counters.jobs();
            inflight += counters.inflight();
            attempts += counters.attempts();
        }
        long execPerSec = (attempts + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE;
        return new TeamUsage(jobs, execPerSec, inflight);
    }

    private record Counters(long jobs, long inflight, long attempts) {}
}
