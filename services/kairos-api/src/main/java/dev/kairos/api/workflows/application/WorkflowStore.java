package dev.kairos.api.workflows.application;

import dev.kairos.domain.id.TeamId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Persistence port for workflow definitions, occurrences and task lifecycle operations. */
public interface WorkflowStore {
    Optional<Workflow> find(TeamId team, UUID id);

    List<Workflow> list(TeamId team, @Nullable String status, @Nullable Key after, int limit);

    Workflow create(TeamId team, UUID id, String actor, WorkflowDefinition definition, Instant now);

    Workflow update(TeamId team, UUID id, long expected, WorkflowDefinition definition, Instant now);

    Workflow change(TeamId team, UUID id, Change change, Instant now);

    Run runNow(TeamId team, UUID id, Instant now);

    List<Run> runs(TeamId team, UUID id, RunFilter filter, @Nullable RunKey after, int limit);

    Optional<Run> findRun(TeamId team, UUID id);

    Run cancelRun(TeamId team, UUID id, Instant now);

    Retry retry(TeamId team, UUID id, String actor, Instant now);

    enum Change {
        PAUSE,
        RESUME,
        CANCEL,
        DELETE
    }

    record Key(Instant createdAt, UUID id) {}

    record RunKey(Instant scheduledAt, UUID id) {}

    record RunFilter(
            @Nullable String status,
            @Nullable Instant from,
            @Nullable Instant to) {}

    record Workflow(
            UUID id,
            String name,
            String status,
            int version,
            JsonNode spec,
            @Nullable Instant nextFireAt,
            Instant createdAt,
            Instant updatedAt) {}

    record Counts(int total, int succeeded, int failed, int cancelled, int skipped) {}

    record Run(
            UUID workflowRunId,
            UUID workflowId,
            int version,
            String status,
            Instant scheduledAt,
            @Nullable Instant startedAt,
            @Nullable Instant finishedAt,
            Counts counts,
            List<Task> tasks) {}

    record Task(
            String taskKey,
            UUID runId,
            String status,
            int attempt,
            List<String> dependsOn,
            Map<String, JsonNode> outputs) {}

    record Retry(UUID workflowRunId, String status, List<String> requeuedTasks) {}
}
