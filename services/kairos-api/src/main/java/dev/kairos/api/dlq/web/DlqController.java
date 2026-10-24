package dev.kairos.api.dlq.web;

import dev.kairos.api.dlq.application.DlqQueries;
import dev.kairos.api.dlq.application.DlqQueryUseCase;
import dev.kairos.api.security.TeamContext;
import dev.kairos.api.support.idempotency.IdempotencyScope;
import dev.kairos.api.support.idempotency.Idempotent;
import dev.kairos.api.support.paging.CursorCodec;
import dev.kairos.api.support.paging.Page;
import dev.kairos.api.support.paging.PageLimit;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.NotFoundException;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.DlqStatus;
import dev.kairos.domain.model.ErrorClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Team scoped DLQ listing and triage operations (LLD APIs 62–64 and 66). */
@RestController
@Tag(name = "Dead letters", description = "Inspect and triage failed runs.")
public class DlqController {
    private static final String LIST = "dead-letters";
    private final DlqQueryUseCase dlq;
    private final CursorCodec cursors;

    /** Creates the controller. */
    public DlqController(DlqQueryUseCase dlq, CursorCodec cursors) {
        this.dlq = Objects.requireNonNull(dlq, "dlq");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    /** LLD API 62. */
    @GetMapping(path = "/v1/dlq", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List dead letters",
            description = "Lists the caller's team's dead-letter entries newest first.")
    public Page<DlqResponse> list(
            TeamContext team,
            @RequestParam(name = "status", required = false) @Nullable String status,
            @RequestParam(name = "endpoint", required = false) @Nullable String endpoint,
            @RequestParam(name = "reason", required = false) @Nullable String reason,
            @RequestParam(name = "limit", required = false) @Nullable Integer limit,
            @RequestParam(name = "cursor", required = false) @Nullable String cursor) {
        DlqQueries.Filter requested =
                new DlqQueries.Filter(DlqQueryUseCase.status(status), endpoint, DlqQueryUseCase.reason(reason));
        Cursor decoded = cursor == null ? null : decode(team, cursor);
        DlqQueries.Filter filter =
                decoded == null ? requested : merge(requested, decoded.filter(), status, endpoint, reason);
        int pageLimit = PageLimit.resolve(limit);
        List<DlqQueries.Entry> rows =
                dlq.list(team.teamId(), filter, decoded == null ? null : decoded.after(), pageLimit + 1);
        boolean more = rows.size() > pageLimit;
        List<DlqQueries.Entry> items = more ? rows.subList(0, pageLimit) : rows;
        return new Page<>(
                items.stream().map(DlqResponse::of).toList(),
                more ? encode(team, filter, items.getLast().key()) : null);
    }

    /** LLD API 63. */
    @GetMapping(path = "/v1/dlq/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a dead letter", description = "Returns one entry owned by the caller's team.")
    public DlqResponse get(TeamContext team, @PathVariable String id) {
        return dlq.get(team.teamId(), parseId(id))
                .map(DlqResponse::of)
                .orElseThrow(() -> new NotFoundException("The DLQ entry does not exist."));
    }

    /** LLD API 64. */
    @PostMapping(
            path = "/v1/dlq/{id}/acknowledge",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Acknowledge a dead letter", description = "Assigns an unresolved entry to a responder.")
    public DlqResponse acknowledge(
            TeamContext team, @PathVariable String id, @Valid @RequestBody AcknowledgeRequest body) {
        return dlq.acknowledge(team.teamId(), parseId(id), body.assignee(), body.notes())
                .map(DlqResponse::of)
                .orElseThrow(() -> new NotFoundException("The DLQ entry does not exist."));
    }

    /** LLD API 66. */
    @PostMapping(
            path = "/v1/dlq/{id}/discard",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Discard a dead letter", description = "Closes an unresolved entry with a reason.")
    public DlqResponse discard(TeamContext team, @PathVariable String id, @Valid @RequestBody DiscardRequest body) {
        return dlq.discard(team.teamId(), parseId(id), team.actor(), body.reason())
                .map(DlqResponse::of)
                .orElseThrow(() -> new NotFoundException("The DLQ entry does not exist."));
    }

    /** LLD API 65. */
    @PostMapping(
            path = "/v1/dlq/{id}/redrive",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Redrive a dead letter", description = "Starts a paced retry series for this dead letter.")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DlqResponse redrive(TeamContext team, @PathVariable String id, @Valid @RequestBody RedriveRequest body) {
        return dlq.redrive(team.teamId(), parseId(id), body.mode(), team.actor())
                .map(DlqResponse::of)
                .orElseThrow(() -> new NotFoundException("The DLQ entry does not exist."));
    }

    /** LLD API 67. */
    @PostMapping(
            path = "/v1/dlq:redrive",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Idempotent(store = IdempotencyScope.CONTROL)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Redrive dead letters in bulk",
            description = "Schedules matching entries through the delayed poller.")
    public BulkRedriveResponse bulkRedrive(
            TeamContext team,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BulkRedriveRequest body) {
        if (!"ORIGINAL".equals(body.mode()) && !"CURRENT".equals(body.mode()))
            throw ValidationFailedException.of("mode", "enum");
        dev.kairos.api.dlq.application.DlqQueries.BulkFilter filter =
                new dev.kairos.api.dlq.application.DlqQueries.BulkFilter(
                        body.filter().endpoint(),
                        DlqQueryUseCase.reason(body.filter().reason()),
                        parseInstant("filter.from", body.filter().from()),
                        parseInstant("filter.to", body.filter().to()));
        List<UUID> candidates = dlq.bulkCandidates(team.teamId(), filter);
        if (body.dryRun()) return new BulkRedriveResponse(candidates.size(), 0, true);
        int scheduled = 0;
        for (UUID candidate : candidates) {
            if (dlq.redrive(team.teamId(), candidate, body.mode(), team.actor()).isPresent()) scheduled++;
        }
        return new BulkRedriveResponse(candidates.size(), scheduled, false);
    }

    private String encode(TeamContext team, DlqQueries.Filter filter, DlqQueries.Key after) {
        return cursors.encode(
                LIST,
                team.teamId(),
                List.of(
                        filter.status() == null ? "" : filter.status().name(),
                        filter.endpoint() == null ? "" : filter.endpoint(),
                        filter.reason() == null ? "" : filter.reason().name(),
                        after.createdAt().toString(),
                        after.dlqId().toString()));
    }

    private Cursor decode(TeamContext team, String encoded) {
        List<String> values = cursors.decode(LIST, team.teamId(), encoded);
        if (values.size() != 5) throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        try {
            return new Cursor(
                    new DlqQueries.Filter(
                            values.get(0).isEmpty() ? null : DlqStatus.valueOf(values.get(0)),
                            emptyNull(values.get(1)),
                            values.get(2).isEmpty() ? null : ErrorClass.valueOf(values.get(2))),
                    new DlqQueries.Key(Instant.parse(values.get(3)), UUID.fromString(values.get(4))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        }
    }

    private static DlqQueries.Filter merge(
            DlqQueries.Filter requested,
            DlqQueries.Filter original,
            @Nullable String status,
            @Nullable String endpoint,
            @Nullable String reason) {
        if ((status != null && !Objects.equals(requested.status(), original.status()))
                || (endpoint != null && !Objects.equals(requested.endpoint(), original.endpoint()))
                || (reason != null && !Objects.equals(requested.reason(), original.reason()))) {
            throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        }
        return original;
    }

    private static UUID parseId(String text) {
        return dev.kairos.domain.id.DlqId.parse(text).value();
    }

    private static @Nullable String emptyNull(String text) {
        return text.isEmpty() ? null : text;
    }

    private static @Nullable Instant parseInstant(String field, @Nullable String text) {
        if (text == null) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw ValidationFailedException.of(field, FieldError.FORMAT);
        }
    }

    private record Cursor(DlqQueries.Filter filter, DlqQueries.Key after) {}

    /** Acknowledge request. */
    public record AcknowledgeRequest(
            @NotBlank @Size(max = 128) String assignee,
            @Nullable @Size(max = 2048) String notes) {}

    /** Discard request. */
    public record DiscardRequest(@NotBlank @Size(max = 2048) String reason) {}

    /** Redrive request. */
    public record RedriveRequest(@NotBlank String mode) {}

    /** Bulk redrive request. */
    public record BulkRedriveRequest(
            @NotNull @Valid BulkFilterRequest filter,
            @NotBlank String mode,
            boolean dryRun) {}

    /** Optional criteria for a bulk redrive; the use case requires at least one. */
    public record BulkFilterRequest(
            @Nullable String endpoint,
            @Nullable String reason,
            @Nullable String from,
            @Nullable String to) {}

    /** Bulk redrive result. */
    public record BulkRedriveResponse(int matched, int scheduled, boolean dryRun) {}

    /** Dead-letter response. */
    public record DlqResponse(
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
        static DlqResponse of(DlqQueries.Entry entry) {
            return new DlqResponse(
                    entry.dlqId(),
                    entry.runId(),
                    entry.jobId(),
                    entry.workflowRunId(),
                    entry.taskKey(),
                    entry.endpoint(),
                    entry.reason(),
                    entry.lastHttpStatus(),
                    entry.lastError(),
                    entry.attempt(),
                    entry.status(),
                    entry.assignee(),
                    entry.notes(),
                    entry.createdAt(),
                    entry.resolvedAt());
        }
    }
}
