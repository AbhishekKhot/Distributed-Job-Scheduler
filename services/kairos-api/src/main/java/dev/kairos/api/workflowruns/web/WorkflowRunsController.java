package dev.kairos.api.workflowruns.web;

import dev.kairos.api.security.TeamContext;
import dev.kairos.api.workflows.application.WorkflowStore;
import dev.kairos.api.workflows.application.WorkflowsUseCase;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.WorkflowRunId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Workflow run detail, cancellation and retry APIs 56–58. */
@RestController
@Tag(name = "Workflow runs", description = "Inspect, cancel and retry a workflow occurrence.")
public class WorkflowRunsController {
    private final WorkflowsUseCase workflows;

    public WorkflowRunsController(WorkflowsUseCase workflows) {
        this.workflows = workflows;
    }

    @GetMapping(path = "/v1/workflow-runs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a workflow run")
    public WorkflowStore.Run get(TeamContext team, @PathVariable String id) {
        return workflows.getRun(team.teamId(), WorkflowRunId.parse(id).value());
    }

    @PostMapping(path = "/v1/workflow-runs/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cancel a workflow run")
    public Summary cancel(TeamContext team, @PathVariable String id) {
        return Summary.of(
                workflows.cancelRun(team.teamId(), WorkflowRunId.parse(id).value()));
    }

    @PostMapping(
            path = "/v1/workflow-runs/{id}/retry",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Retry failed workflow tasks")
    public ResponseEntity<WorkflowStore.Retry> retry(
            TeamContext team, @PathVariable String id, @RequestBody JsonNode body) {
        if (!body.isObject()
                || !body.path("from").isString()
                || !body.path("from").asString().equals("FAILED_TASKS"))
            throw ValidationFailedException.of("from", FieldError.FORMAT);
        return ResponseEntity.accepted()
                .body(workflows.retry(team.teamId(), WorkflowRunId.parse(id).value(), team.actor()));
    }

    public record Summary(
            java.util.UUID workflowRunId,
            String status,
            java.time.Instant scheduledAt,
            java.time.@org.jspecify.annotations.Nullable Instant finishedAt,
            WorkflowStore.Counts counts) {
        static Summary of(WorkflowStore.Run run) {
            return new Summary(run.workflowRunId(), run.status(), run.scheduledAt(), run.finishedAt(), run.counts());
        }
    }
}
