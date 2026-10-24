package dev.kairos.api.stats.infrastructure;

import dev.kairos.api.stats.application.StatsQueries;
import dev.kairos.api.stats.application.TeamStats;
import dev.kairos.api.stats.application.TeamStats.EndpointStats;
import dev.kairos.domain.id.TeamId;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads counters and exact nearest-rank percentiles across cells. */
public final class JdbcStatsQueries implements StatsQueries {
    private static final String TEAM_MINUTES = """
            SELECT COALESCE(sum(attempts),0), COALESCE(sum(successes),0), COALESCE(sum(dead_lettered),0)
              FROM scheduler.team_stats_minute WHERE team_id=:team AND minute>=:from
            """;
    private static final String ENDPOINT_MINUTES = """
            SELECT endpoint_id, sum(attempts) AS attempts, sum(successes) AS successes
              FROM scheduler.endpoint_health_minute WHERE team_id=:team AND minute>=:from GROUP BY endpoint_id
            """;
    private static final String LATENCY_HISTOGRAM = """
            SELECT endpoint_id, latency_ms, count(*) AS samples
              FROM scheduler.run_history
             WHERE team_id=:team AND finished_at>=:from AND latency_ms IS NOT NULL
             GROUP BY endpoint_id, latency_ms
            """;
    private static final String OPEN_DLQ = """
            SELECT count(*) FROM scheduler.dead_letters WHERE team_id=:team AND status IN (1,2)
            """;

    private final JdbcClient control;
    private final CellRouter cells;
    private final Clock clock;

    /** Creates the query adapter. */
    public JdbcStatsQueries(JdbcClient control, CellRouter cells, Clock clock) {
        this.control = Objects.requireNonNull(control, "control");
        this.cells = Objects.requireNonNull(cells, "cells");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public TeamStats get(TeamId team, String window, Duration duration) {
        Instant now = clock.instant();
        Instant preciseFrom = now.minus(duration);
        OffsetDateTime fromMinute = preciseFrom.truncatedTo(ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC);
        OffsetDateTime fromHistory = preciseFrom.atOffset(ZoneOffset.UTC);
        Totals totals = new Totals();
        Map<UUID, EndpointTotals> endpoints = new HashMap<>();
        TreeMap<Integer, Long> allLatencies = new TreeMap<>();
        long openDlq = 0;
        for (Cell cell : cells.allCells()) {
            cell.jdbc()
                    .sql(TEAM_MINUTES)
                    .param("team", team.value())
                    .param("from", fromMinute)
                    .query((rs, row) -> {
                        totals.attempts += rs.getLong(1);
                        totals.successes += rs.getLong(2);
                        totals.deadLettered += rs.getLong(3);
                        return 0;
                    })
                    .single();
            cell.jdbc()
                    .sql(ENDPOINT_MINUTES)
                    .param("team", team.value())
                    .param("from", fromMinute)
                    .query((rs, row) -> {
                        endpoints
                                .computeIfAbsent(
                                        rs.getObject("endpoint_id", UUID.class), ignored -> new EndpointTotals())
                                .add(rs.getLong("attempts"), rs.getLong("successes"));
                        return 0;
                    })
                    .list();
            cell.jdbc()
                    .sql(LATENCY_HISTOGRAM)
                    .param("team", team.value())
                    .param("from", fromHistory)
                    .query((rs, row) -> {
                        UUID endpoint = rs.getObject("endpoint_id", UUID.class);
                        int latency = rs.getInt("latency_ms");
                        long samples = rs.getLong("samples");
                        allLatencies.merge(latency, samples, Long::sum);
                        endpoints
                                .computeIfAbsent(endpoint, ignored -> new EndpointTotals())
                                .latencies
                                .merge(latency, samples, Long::sum);
                        return 0;
                    })
                    .list();
            openDlq += cell.jdbc()
                    .sql(OPEN_DLQ)
                    .param("team", team.value())
                    .query(Long.class)
                    .single();
        }
        Map<UUID, String> names = endpointNames(team, endpoints.keySet());
        List<EndpointStats> byEndpoint = endpoints.entrySet().stream()
                .map(entry -> {
                    EndpointTotals value = entry.getValue();
                    return new EndpointStats(
                            names.getOrDefault(entry.getKey(), "unknown"),
                            value.attempts,
                            rate(value.successes, value.attempts),
                            percentile(value.latencies, 0.95));
                })
                .sorted(Comparator.comparing(EndpointStats::endpoint))
                .toList();
        return new TeamStats(
                window,
                totals.attempts,
                rate(totals.successes, totals.attempts),
                percentile(allLatencies, 0.50),
                percentile(allLatencies, 0.95),
                totals.deadLettered,
                openDlq,
                byEndpoint);
    }

    private Map<UUID, String> endpointNames(TeamId team, java.util.Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return control
                .sql(
                        "SELECT endpoint_id,name FROM scheduler.api_endpoints WHERE team_id=:team AND endpoint_id IN (:ids)")
                .param("team", team.value())
                .param("ids", ids)
                .query((rs, row) -> Map.entry(rs.getObject("endpoint_id", UUID.class), rs.getString("name")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static double rate(long successes, long attempts) {
        return attempts == 0 ? 0.0 : (double) successes / attempts;
    }

    private static long percentile(TreeMap<Integer, Long> histogram, double quantile) {
        long count = histogram.values().stream().mapToLong(Long::longValue).sum();
        if (count == 0) return 0;
        long rank = Math.max(1, (long) Math.ceil(quantile * count));
        long seen = 0;
        for (Map.Entry<Integer, Long> entry : histogram.entrySet()) {
            seen += entry.getValue();
            if (seen >= rank) return entry.getKey();
        }
        return histogram.lastKey();
    }

    private static final class Totals {
        private long attempts;
        private long successes;
        private long deadLettered;
    }

    private static final class EndpointTotals {
        private long attempts;
        private long successes;
        private final TreeMap<Integer, Long> latencies = new TreeMap<>();

        private void add(long attemptCount, long successCount) {
            attempts += attemptCount;
            successes += successCount;
        }
    }
}
