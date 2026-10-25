package dev.kairos.api.workflows.web;

import dev.kairos.api.security.TeamContext;
import dev.kairos.api.support.etag.ETags;
import dev.kairos.api.support.etag.IfMatch;
import dev.kairos.api.support.idempotency.IdempotencyScope;
import dev.kairos.api.support.idempotency.Idempotent;
import dev.kairos.api.support.paging.CursorCodec;
import dev.kairos.api.support.paging.Page;
import dev.kairos.api.support.paging.PageLimit;
import dev.kairos.api.workflows.application.WorkflowStore;
import dev.kairos.api.workflows.application.WorkflowStore.Workflow;
import dev.kairos.api.workflows.application.WorkflowsUseCase;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.WorkflowId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Workflow definition and occurrence APIs 46–55. */
@RestController
@Tag(name = "Workflows", description = "Schedule and operate versioned HTTP task graphs.")
public class WorkflowsController {
    private final WorkflowsUseCase workflows;
    private final CursorCodec cursors;

    public WorkflowsController(WorkflowsUseCase workflows, CursorCodec cursors) {
        this.workflows = workflows;
        this.cursors = cursors;
    }

    @PostMapping(
            path = "/v1/workflows",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Idempotent(store = IdempotencyScope.NAME_SHARD)
    @Operation(summary = "Create a workflow")
    public ResponseEntity<WorkflowResponse> create(TeamContext team, @RequestBody String body) {
        Workflow created = workflows.create(team.teamId(), team.actor(), body);
        return ResponseEntity.created(URI.create("/v1/workflows/" + created.id()))
                .header(HttpHeaders.ETAG, ETags.of(created.version()))
                .body(WorkflowResponse.of(created));
    }

    @GetMapping(path = "/v1/workflows", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List workflows")
    public Page<Summary> list(
            TeamContext team,
            @RequestParam(name = "status", required = false) @Nullable String status,
            @RequestParam(name = "limit", required = false) @Nullable Integer limit,
            @RequestParam(name = "cursor", required = false) @Nullable String cursor) {
        if (status != null && !List.of("ACTIVE", "PAUSED", "CANCELLED").contains(status))
            throw ValidationFailedException.of("status", "enum");
        WorkflowStore.Key after = null;
        if (cursor != null) {
            List<String> fields = cursors.decode("workflows", team.teamId(), cursor);
            try {
                if (fields.size() != 3 || (status != null && !status.equals(fields.get(0))))
                    throw new IllegalArgumentException();
                status = empty(fields.get(0));
                after = new WorkflowStore.Key(Instant.parse(fields.get(1)), UUID.fromString(fields.get(2)));
            } catch (RuntimeException invalid) {
                throw ValidationFailedException.of("cursor", FieldError.FORMAT);
            }
        }
        WorkflowsUseCase.Page result = workflows.list(team.teamId(), status, after, PageLimit.resolve(limit));
        String next = result.next() == null
                ? null
                : cursors.encode(
                        "workflows",
                        team.teamId(),
                        List.of(
                                status == null ? "" : status,
                                result.next().createdAt().toString(),
                                result.next().id().toString()));
        return new Page<>(result.items().stream().map(Summary::of).toList(), next);
    }

    @GetMapping(path = "/v1/workflows/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a workflow")
    public ResponseEntity<WorkflowResponse> get(TeamContext team, @PathVariable String id) {
        Workflow workflow = workflows.get(team.teamId(), WorkflowId.parse(id).value());
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.of(workflow.version()))
                .body(WorkflowResponse.of(workflow));
    }

    @PutMapping(
            path = "/v1/workflows/{id}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update a workflow")
    public ResponseEntity<WorkflowResponse> update(
            TeamContext team, @PathVariable String id, @IfMatch long expected, @RequestBody String body) {
        Workflow workflow = workflows.update(team.teamId(), WorkflowId.parse(id).value(), expected, body);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.of(workflow.version()))
                .body(WorkflowResponse.of(workflow));
    }

    @DeleteMapping("/v1/workflows/{id}")
    @Operation(summary = "Delete a workflow")
    public ResponseEntity<Void> delete(TeamContext team, @PathVariable String id) {
        workflows.change(team.teamId(), WorkflowId.parse(id).value(), WorkflowStore.Change.DELETE);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/v1/workflows/{id}/pause", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Pause a workflow")
    public Summary pause(TeamContext team, @PathVariable String id) {
        return change(team, id, WorkflowStore.Change.PAUSE);
    }

    @PostMapping(path = "/v1/workflows/{id}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Resume a workflow")
    public Summary resume(TeamContext team, @PathVariable String id) {
        return change(team, id, WorkflowStore.Change.RESUME);
    }

    @PostMapping(path = "/v1/workflows/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cancel a workflow")
    public Summary cancel(TeamContext team, @PathVariable String id) {
        return change(team, id, WorkflowStore.Change.CANCEL);
    }

    private Summary change(TeamContext team, String id, WorkflowStore.Change change) {
        return Summary.of(workflows.change(team.teamId(), WorkflowId.parse(id).value(), change));
    }

    @PostMapping(path = "/v1/workflows/{id}/runnow", produces = MediaType.APPLICATION_JSON_VALUE)
    @Idempotent(store = IdempotencyScope.PATH_ID_SHARD)
    @Operation(summary = "Run a workflow now")
    public ResponseEntity<Started> runNow(TeamContext team, @PathVariable String id) {
        var run = workflows.runNow(team.teamId(), WorkflowId.parse(id).value());
        return ResponseEntity.accepted()
                .location(URI.create("/v1/workflow-runs/" + run.workflowRunId()))
                .body(new Started(run.workflowRunId(), run.status()));
    }

    @GetMapping(path = "/v1/workflows/{id}/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List workflow runs")
    public Page<RunSummary> runs(
            TeamContext team,
            @PathVariable String id,
            @RequestParam(name = "status", required = false) @Nullable String status,
            @RequestParam(name = "from", required = false) @Nullable String from,
            @RequestParam(name = "to", required = false) @Nullable String to,
            @RequestParam(name = "limit", required = false) @Nullable Integer limit,
            @RequestParam(name = "cursor", required = false) @Nullable String cursor) {
        UUID workflow = WorkflowId.parse(id).value();
        if (status != null
                && !List.of("RUNNING", "BLOCKED", "SUCCEEDED", "FAILED", "CANCELLED")
                        .contains(status)) throw ValidationFailedException.of("status", "enum");
        WorkflowStore.RunFilter requested = new WorkflowStore.RunFilter(status, time(from), time(to));
        WorkflowStore.RunFilter filter = requested;
        WorkflowStore.RunKey after = null;
        if (cursor != null) {
            List<String> fields = cursors.decode("workflow-runs:" + workflow, team.teamId(), cursor);
            try {
                if (fields.size() != 5) throw new IllegalArgumentException();
                filter = new WorkflowStore.RunFilter(
                        empty(fields.get(0)), time(empty(fields.get(1))), time(empty(fields.get(2))));
                if ((status != null && !Objects.equals(status, filter.status()))
                        || (from != null && !Objects.equals(requested.from(), filter.from()))
                        || (to != null && !Objects.equals(requested.to(), filter.to())))
                    throw new IllegalArgumentException();
                after = new WorkflowStore.RunKey(Instant.parse(fields.get(3)), UUID.fromString(fields.get(4)));
            } catch (RuntimeException invalid) {
                throw ValidationFailedException.of("cursor", FieldError.FORMAT);
            }
        }
        var result = workflows.runs(team.teamId(), workflow, filter, after, PageLimit.resolve(limit));
        String next = result.next() == null
                ? null
                : cursors.encode(
                        "workflow-runs:" + workflow,
                        team.teamId(),
                        List.of(
                                filter.status() == null ? "" : filter.status(),
                                filter.from() == null ? "" : filter.from().toString(),
                                filter.to() == null ? "" : filter.to().toString(),
                                result.next().scheduledAt().toString(),
                                result.next().id().toString()));
        return new Page<>(result.items().stream().map(RunSummary::of).toList(), next);
    }

    private static @Nullable Instant time(@Nullable String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException invalid) {
            throw ValidationFailedException.of("from", FieldError.FORMAT);
        }
    }

    private static @Nullable String empty(String value) {
        return value.isEmpty() ? null : value;
    }

    public record Started(UUID workflowRunId, String status) {}

    public record Summary(
            UUID id,
            String name,
            String status,
            int taskCount,
            @Nullable Instant nextFireAt) {
        static Summary of(Workflow workflow) {
            return new Summary(
                    workflow.id(),
                    workflow.name(),
                    workflow.status(),
                    workflow.spec().path("tasks").size(),
                    workflow.nextFireAt());
        }
    }

    public record WorkflowResponse(
            UUID id,
            String name,
            JsonNode schedule,
            int priority,
            String onTaskFailure,
            int reviewTimeoutSec,
            JsonNode tasks,
            String status,
            int version,
            @Nullable Instant nextFireAt,
            Instant createdAt,
            Instant updatedAt) {
        static WorkflowResponse of(Workflow workflow) {
            JsonNode body = workflow.spec();
            return new WorkflowResponse(
                    workflow.id(),
                    workflow.name(),
                    body.path("schedule"),
                    body.path("priority").asInt(5),
                    body.path("onTaskFailure").asString(),
                    body.path("reviewTimeoutSec").asInt(86400),
                    body.path("tasks"),
                    workflow.status(),
                    workflow.version(),
                    workflow.nextFireAt(),
                    workflow.createdAt(),
                    workflow.updatedAt());
        }
    }

    public record RunSummary(
            UUID workflowRunId,
            String status,
            Instant scheduledAt,
            @Nullable Instant finishedAt,
            WorkflowStore.Counts counts) {
        public static RunSummary of(WorkflowStore.Run run) {
            return new RunSummary(run.workflowRunId(), run.status(), run.scheduledAt(), run.finishedAt(), run.counts());
        }
    }
}
