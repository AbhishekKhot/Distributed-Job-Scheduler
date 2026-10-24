package dev.kairos.api.stats.application;

import java.util.List;

/** Statistics for one team's trailing time window. */
public record TeamStats(
        String window,
        long attempts,
        double successRate,
        long p50LatencyMs,
        long p95LatencyMs,
        long deadLettered,
        long openDlq,
        List<EndpointStats> byEndpoint) {
    /** Per-endpoint execution counters. */
    public record EndpointStats(String endpoint, long attempts, double successRate, long p95LatencyMs) {}
}
