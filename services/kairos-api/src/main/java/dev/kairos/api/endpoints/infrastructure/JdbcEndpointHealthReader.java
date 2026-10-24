package dev.kairos.api.endpoints.infrastructure;

import dev.kairos.api.endpoints.application.EndpointHealthReader;
import dev.kairos.domain.id.TeamId;
import dev.kairos.persistence.cell.CellRouter;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Aggregates endpoint health counters and nearest-rank latency percentiles across cells. */
public final class JdbcEndpointHealthReader implements EndpointHealthReader {
    private final CellRouter cells;
    private final Clock clock;

    public JdbcEndpointHealthReader(CellRouter cells, Clock clock) {
        this.cells = cells;
        this.clock = clock;
    }

    @Override
    public EndpointHealth read(TeamId team, UUID endpoint, String window, Duration duration) {
        var from = Timestamp.from(clock.instant().minus(duration));
        Totals totals = new Totals();
        TreeMap<Integer, Long> latencies = new TreeMap<>();
        for (var cell : cells.allCells()) {
            cell.jdbc()
                    .sql("""
                    SELECT COALESCE(sum(attempts), 0) AS attempts, COALESCE(sum(successes), 0) AS successes,
                           COALESCE(sum(dead_lettered), 0) AS dead_lettered
                      FROM scheduler.endpoint_health_minute
                     WHERE team_id = :team AND endpoint_id = :endpoint AND minute >= :from
                    """)
                    .param("team", team.value())
                    .param("endpoint", endpoint)
                    .param("from", from)
                    .query((rs, row) -> {
                        totals.attempts += rs.getLong("attempts");
                        totals.successes += rs.getLong("successes");
                        totals.deadLettered += rs.getLong("dead_lettered");
                        return 1;
                    })
                    .single();
            cell.jdbc()
                    .sql("""
                    SELECT latency_ms, count(*) AS samples FROM scheduler.run_history
                     WHERE team_id = :team AND endpoint_id = :endpoint AND finished_at >= :from
                       AND latency_ms IS NOT NULL
                     GROUP BY latency_ms
                    """)
                    .param("team", team.value())
                    .param("endpoint", endpoint)
                    .param("from", from)
                    .query((rs, row) -> Map.entry(rs.getInt("latency_ms"), rs.getLong("samples")))
                    .list()
                    .forEach(entry -> latencies.merge(entry.getKey(), entry.getValue(), Long::sum));
        }
        long sampleCount =
                latencies.values().stream().mapToLong(Long::longValue).sum();
        long rank = sampleCount == 0 ? 0 : (long) Math.ceil(sampleCount * 0.95);
        long p95 = 0;
        long cumulative = 0;
        for (var sample : latencies.entrySet()) {
            cumulative += sample.getValue();
            if (cumulative >= rank) {
                p95 = sample.getKey();
                break;
            }
        }
        double failureRatio =
                totals.attempts == 0 ? 0d : (double) (totals.attempts - totals.successes) / totals.attempts;
        return new EndpointHealth(
                window, totals.attempts, totals.successes, failureRatio, p95, totals.deadLettered, "UNKNOWN");
    }

    private static final class Totals {
        long attempts;
        long successes;
        long deadLettered;
    }
}
