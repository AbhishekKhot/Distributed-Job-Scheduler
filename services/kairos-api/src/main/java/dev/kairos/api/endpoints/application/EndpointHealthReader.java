package dev.kairos.api.endpoints.application;

import dev.kairos.domain.id.TeamId;
import java.time.Duration;
import java.util.UUID;

/** Reads endpoint counters and latency summaries from all cells. */
public interface EndpointHealthReader {
    EndpointHealth read(TeamId team, UUID endpoint, String window, Duration duration);

    record EndpointHealth(
            String window,
            long attempts,
            long successes,
            double failureRatio,
            long p95LatencyMs,
            long deadLettered,
            String breaker) {}
}
