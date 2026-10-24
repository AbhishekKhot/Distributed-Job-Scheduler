package dev.kairos.executor.consume.application;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Atomically applies the team and endpoint concurrency caps within this executor pod. */
public final class LocalDispatchLimiter {

    /** Result of checking both local concurrency limits. */
    public enum Result {
        /** The pair of limits was acquired. */
        ACQUIRED,
        /** The team is at its in-flight cap. */
        TEAM_LIMIT,
        /** The endpoint is at its concurrency cap. */
        ENDPOINT_LIMIT
    }

    private final Map<UUID, Integer> teams = new HashMap<>();
    private final Map<UUID, Integer> endpoints = new HashMap<>();

    /** Acquires both limits or neither. */
    public synchronized Result acquire(UUID teamId, UUID endpointId, DispatchLimits limits) {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(limits, "limits");
        if (teams.getOrDefault(teamId, 0) >= limits.teamMaxInflight()
                || endpoints.getOrDefault(endpointId, 0) >= limits.endpointMaxConcurrency()) {
            return teams.getOrDefault(teamId, 0) >= limits.teamMaxInflight()
                    ? Result.TEAM_LIMIT
                    : Result.ENDPOINT_LIMIT;
        }
        teams.merge(teamId, 1, Integer::sum);
        endpoints.merge(endpointId, 1, Integer::sum);
        return Result.ACQUIRED;
    }

    /** Releases a previously acquired pair of limits. */
    public synchronized void release(UUID teamId, UUID endpointId) {
        decrement(teams, teamId);
        decrement(endpoints, endpointId);
    }

    private static void decrement(Map<UUID, Integer> counts, UUID id) {
        Integer count = counts.get(id);
        if (count == null) {
            throw new IllegalStateException("Dispatch capacity was not acquired");
        }
        if (count == 1) {
            counts.remove(id);
        } else {
            counts.put(id, count - 1);
        }
    }
}
