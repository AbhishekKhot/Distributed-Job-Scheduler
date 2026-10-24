package dev.kairos.api.dlq.application;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.DlqStatus;
import dev.kairos.domain.model.ErrorClass;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Validates DLQ filters and delegates triage actions. */
public final class DlqQueryUseCase {
    private final DlqQueries queries;

    /** Creates the use case. */
    public DlqQueryUseCase(DlqQueries queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    /** Lists entries. */
    public List<DlqQueries.Entry> list(
            TeamId team, DlqQueries.Filter filter, DlqQueries.@Nullable Key after, int limit) {
        validateEndpoint(filter.endpoint());
        return queries.list(team, filter, after, limit);
    }

    /** Reads an entry. */
    public Optional<DlqQueries.Entry> get(TeamId team, UUID id) {
        return queries.get(team, id);
    }

    /** Acknowledges an entry. */
    public Optional<DlqQueries.Entry> acknowledge(TeamId team, UUID id, String assignee, @Nullable String notes) {
        if (assignee.isBlank() || assignee.length() > 128)
            throw ValidationFailedException.of("assignee", FieldError.LENGTH);
        if (notes != null && notes.length() > 2048) throw ValidationFailedException.of("notes", FieldError.LENGTH);
        return queries.acknowledge(team, id, assignee, notes);
    }

    /** Discards an entry. */
    public Optional<DlqQueries.Entry> discard(TeamId team, UUID id, String actor, String reason) {
        if (reason.isBlank() || reason.length() > 2048) throw ValidationFailedException.of("reason", FieldError.LENGTH);
        return queries.discard(team, id, actor, reason);
    }

    /** Redrives an unresolved entry. */
    public Optional<DlqQueries.Entry> redrive(TeamId team, UUID id, String mode, String actor) {
        if (!"ORIGINAL".equals(mode) && !"CURRENT".equals(mode)) throw ValidationFailedException.of("mode", "enum");
        return queries.redrive(team, id, mode, actor);
    }

    /** Lists bulk redrive candidates after validating its filters. */
    public List<UUID> bulkCandidates(TeamId team, DlqQueries.BulkFilter filter) {
        validateEndpoint(filter.endpoint());
        if (filter.endpoint() == null && filter.reason() == null && filter.from() == null && filter.to() == null)
            throw ValidationFailedException.of("filter", FieldError.REQUIRED);
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to()))
            throw ValidationFailedException.of("filter.to", FieldError.RANGE);
        return queries.bulkCandidates(team, filter);
    }

    private static void validateEndpoint(@Nullable String endpoint) {
        if (endpoint != null && (endpoint.length() > 63 || !endpoint.matches("^[a-z][a-z0-9-]{1,62}$"))) {
            throw ValidationFailedException.of("endpoint", FieldError.FORMAT);
        }
    }

    /** Parses a list status. */
    public static @Nullable DlqStatus status(@Nullable String value) {
        if (value == null) return null;
        try {
            return DlqStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ValidationFailedException.of("status", "enum");
        }
    }

    /** Parses a list error class. */
    public static @Nullable ErrorClass reason(@Nullable String value) {
        if (value == null) return null;
        try {
            return ErrorClass.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ValidationFailedException.of("reason", "enum");
        }
    }
}
