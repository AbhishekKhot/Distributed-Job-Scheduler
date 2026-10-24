package dev.kairos.api.dlq.application;

import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.DlqStatus;
import dev.kairos.domain.model.ErrorClass;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Team-scoped DLQ reads and triage actions. */
public interface DlqQueries {
    /** Lists entries across cells in descending creation order. */
    List<Entry> list(TeamId team, Filter filter, @Nullable Key after, int limit);

    /** Reads one entry from the cell embedded in its id. */
    Optional<Entry> get(TeamId team, UUID dlqId);

    /** Assigns an unresolved entry. */
    Optional<Entry> acknowledge(TeamId team, UUID dlqId, String assignee, @Nullable String notes);

    /** Discards an unresolved entry. */
    Optional<Entry> discard(TeamId team, UUID dlqId, String actor, String reason);

    /** Redrives one unresolved entry using its original or current definition. */
    Optional<Entry> redrive(TeamId team, UUID dlqId, String mode, String actor);

    /** Finds unresolved entries matching a bulk redrive request. */
    List<UUID> bulkCandidates(TeamId team, BulkFilter filter);

    /** Filters of the DLQ list. */
    record Filter(
            @Nullable DlqStatus status,
            @Nullable String endpoint,
            @Nullable ErrorClass reason) {}

    /** Required criteria and time range for a bulk redrive. */
    record BulkFilter(
            @Nullable String endpoint,
            @Nullable ErrorClass reason,
            @Nullable Instant from,
            @Nullable Instant to) {}

    /** Page cursor key. */
    record Key(Instant createdAt, UUID dlqId) {}

    /** One redacted dead-letter entry. */
    record Entry(
            UUID dlqId,
            UUID runId,
            @Nullable UUID jobId,
            @Nullable UUID workflowRunId,
            @Nullable String taskKey,
            String endpoint,
            ErrorClass reason,
            @Nullable Integer lastHttpStatus,
            @Nullable String lastError,
            int attempt,
            DlqStatus status,
            @Nullable String assignee,
            @Nullable String notes,
            Instant createdAt,
            @Nullable Instant resolvedAt) {
        public Key key() {
            return new Key(createdAt, dlqId);
        }
    }
}
