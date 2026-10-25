package dev.kairos.state.stats.infrastructure;

import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.state.stats.application.StatsBuffer.Snapshot;
import dev.kairos.state.stats.application.StatsWriter;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** Absolute monotonic upserts make replay after an ambiguous or partial-cell commit harmless. */
public final class JdbcStatsWriter implements StatsWriter {
    private static final String ENDPOINT = """
            INSERT INTO scheduler.endpoint_health_minute AS stored(endpoint_id,minute,node_id,team_id,attempts,successes,retryable,
                non_retryable,dead_lettered,latency_sum_ms,latency_max_ms)
            VALUES (:endpoint,:minute,:node,:team,:attempts,:successes,:retryable,:nonRetryable,:dead,:sum,:max)
            ON CONFLICT(endpoint_id,minute,node_id) DO UPDATE SET
                attempts=GREATEST(stored.attempts,EXCLUDED.attempts),successes=GREATEST(stored.successes,EXCLUDED.successes),
                retryable=GREATEST(stored.retryable,EXCLUDED.retryable),non_retryable=GREATEST(stored.non_retryable,EXCLUDED.non_retryable),
                dead_lettered=GREATEST(stored.dead_lettered,EXCLUDED.dead_lettered),latency_sum_ms=GREATEST(stored.latency_sum_ms,EXCLUDED.latency_sum_ms),
                latency_max_ms=GREATEST(stored.latency_max_ms,EXCLUDED.latency_max_ms)
            """;
    private static final String TEAM = """
            INSERT INTO scheduler.team_stats_minute AS stored(team_id,minute,node_id,attempts,successes,dead_lettered,latency_sum_ms)
            VALUES (:team,:minute,:node,:attempts,:successes,:dead,:sum)
            ON CONFLICT(team_id,minute,node_id) DO UPDATE SET
                attempts=GREATEST(stored.attempts,EXCLUDED.attempts),successes=GREATEST(stored.successes,EXCLUDED.successes),
                dead_lettered=GREATEST(stored.dead_lettered,EXCLUDED.dead_lettered),latency_sum_ms=GREATEST(stored.latency_sum_ms,EXCLUDED.latency_sum_ms)
            """;
    private final CellRouter cells;

    public JdbcStatsWriter(CellRouter cells) {
        this.cells = cells;
    }

    @Override
    public void write(String nodeId, List<Snapshot> snapshots) {
        Map<Integer, List<Snapshot>> groups = snapshots.stream()
                .collect(Collectors.groupingBy(snapshot -> snapshot.key().cellId()));
        for (var cell : cells.allCells().stream()
                .sorted(Comparator.comparingInt(dev.kairos.persistence.cell.Cell::id))
                .toList()) {
            var group = groups.get(cell.id());
            if (group == null) continue;
            cell.transactions().execute(jdbc -> {
                cell.database()
                        .batchUpdate(
                                ENDPOINT,
                                group.stream()
                                        .filter(s -> s.key().endpointId() != null)
                                        .toList(),
                                s -> parameters(nodeId, s));
                cell.database()
                        .batchUpdate(
                                TEAM,
                                group.stream()
                                        .filter(s -> s.key().endpointId() == null)
                                        .toList(),
                                s -> parameters(nodeId, s));
                return null;
            });
            groups.remove(cell.id());
        }
        if (!groups.isEmpty()) throw new IllegalStateException("Statistics cell is not configured");
    }

    private static MapSqlParameterSource parameters(String node, Snapshot snapshot) {
        var key = snapshot.key();
        var counts = snapshot.counts();
        return new MapSqlParameterSource()
                .addValue("endpoint", key.endpointId())
                .addValue("minute", key.minute().atOffset(ZoneOffset.UTC))
                .addValue("node", node)
                .addValue("team", key.teamId())
                .addValue("attempts", counts.attempts())
                .addValue("successes", counts.successes())
                .addValue("retryable", counts.retryable())
                .addValue("nonRetryable", counts.nonRetryable())
                .addValue("dead", counts.deadLettered())
                .addValue("sum", counts.latencySumMs())
                .addValue("max", counts.latencyMaxMs());
    }
}
