package dev.kairos.api.runs.application;

import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.run.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Queries and cancels a team's runs across its cell databases. */
public interface RunQueries {
    /** Lists runs in descending occurrence order. */
    List<RunSummary> list(TeamId team, Filter filter, @Nullable Key after, int limit);

    /** Reads a run and all retained attempts from the cell encoded in its id. */
    Optional<RunDetails> get(TeamId team, UUID runId);

    /** Cancels a run, returning its post-cancel representation or empty when absent. */
    Optional<RunSummary> cancel(TeamId team, UUID runId);

    /** List filters. */
    record Filter(
            @Nullable RunStatus status,
            @Nullable UUID jobId,
            @Nullable String endpoint,
            @Nullable Instant from,
            @Nullable Instant to) {}

    /** Stable key for a page cursor. */
    record Key(Instant scheduledAt, UUID runId) {}

    /** Fields shared by run list and cancellation responses. */
    record RunSummary(
            UUID runId,
            @Nullable UUID jobId,
            @Nullable UUID workflowRunId,
            @Nullable String taskKey,
            RunStatus status,
            int attempt,
            Instant scheduledAt,
            @Nullable Instant finishedAt,
            @Nullable Integer httpStatus,
            @Nullable Integer latencyMs) {
        public Key key() {
            return new Key(scheduledAt, runId);
        }
    }

    /** One retained run and its attempt history. */
    record RunDetails(
            RunSummary current,
            @Nullable String trigger,
            @Nullable Instant startedAt,
            @Nullable String errorClass,
            @Nullable String error,
            List<Attempt> attempts) {
        public RunDetails {
            attempts = List.copyOf(attempts);
        }
    }

    /** A single attempt from {@code run_history}. */
    record Attempt(
            int attempt,
            @Nullable String outcome,
            @Nullable Integer httpStatus,
            @Nullable Integer latencyMs,
            Instant finishedAt) {}
}
