package dev.kairos.api.runs.web;

import dev.kairos.api.runs.application.RunQueries.Attempt;
import dev.kairos.api.runs.application.RunQueries.Filter;
import dev.kairos.api.runs.application.RunQueries.Key;
import dev.kairos.api.runs.application.RunQueries.RunDetails;
import dev.kairos.api.runs.application.RunQueries.RunSummary;
import dev.kairos.api.runs.application.RunQueryUseCase;
import dev.kairos.api.security.TeamContext;
import dev.kairos.api.support.paging.CursorCodec;
import dev.kairos.api.support.paging.Page;
import dev.kairos.api.support.paging.PageLimit;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.NotFoundException;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.run.RunStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Team scoped run listing, inspection and cancellation (LLD APIs 59–61). */
@RestController
@Tag(name = "Runs", description = "Read, inspect and cancel job or workflow task runs.")
public class RunsController {
    private static final String LIST = "runs";
    private final RunQueryUseCase runs;
    private final CursorCodec cursors;

    /** Creates the controller. */
    public RunsController(RunQueryUseCase runs, CursorCodec cursors) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    /** LLD API 59. */
    @GetMapping(path = "/v1/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List runs", description = "Lists the caller's team's runs newest first across every cell.")
    public Page<RunResponse> list(
            TeamContext team,
            @RequestParam(name = "status", required = false) @Nullable String status,
            @RequestParam(name = "job", required = false) @Nullable String job,
            @RequestParam(name = "endpoint", required = false) @Nullable String endpoint,
            @RequestParam(name = "from", required = false) @Nullable String from,
            @RequestParam(name = "to", required = false) @Nullable String to,
            @RequestParam(name = "limit", required = false) @Nullable Integer limit,
            @RequestParam(name = "cursor", required = false) @Nullable String cursor) {
        Filter requested = new Filter(
                parseStatus(status), parseUuid("job", job), endpoint, parseTime("from", from), parseTime("to", to));
        Cursor decoded = cursor == null ? null : decode(team, cursor);
        Filter filter =
                decoded == null ? requested : merge(requested, decoded.filter(), status, job, endpoint, from, to);
        List<RunSummary> rows = runs.list(
                team.teamId(), filter, decoded == null ? null : decoded.after(), PageLimit.resolve(limit) + 1);
        boolean more = rows.size() > PageLimit.resolve(limit);
        List<RunSummary> page = more ? rows.subList(0, PageLimit.resolve(limit)) : rows;
        String next = more ? encode(team, filter, page.getLast().key()) : null;
        return new Page<>(page.stream().map(RunResponse::of).toList(), next);
    }

    /** LLD API 60. */
    @GetMapping(path = "/v1/runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a run", description = "Returns the run's current state and retained attempts.")
    public RunDetailsResponse get(TeamContext team, @PathVariable String runId) {
        UUID id = dev.kairos.domain.id.RunId.parse(runId).value();
        RunDetails result =
                runs.get(team.teamId(), id).orElseThrow(() -> new NotFoundException("The run does not exist."));
        return RunDetailsResponse.of(result);
    }

    /** LLD API 61. */
    @PostMapping(path = "/v1/runs/{runId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Cancel a run",
            description = "Cancels a pending run or sends a fenced cancel to its executor.")
    public RunResponse cancel(TeamContext team, @PathVariable String runId) {
        UUID id = dev.kairos.domain.id.RunId.parse(runId).value();
        return runs.cancel(team.teamId(), id)
                .map(RunResponse::of)
                .orElseThrow(() -> new NotFoundException("The run does not exist."));
    }

    private String encode(TeamContext team, Filter filter, Key after) {
        return cursors.encode(
                LIST,
                team.teamId(),
                List.of(
                        filter.status() == null ? "" : filter.status().name(),
                        filter.jobId() == null ? "" : filter.jobId().toString(),
                        filter.endpoint() == null ? "" : filter.endpoint(),
                        filter.from() == null ? "" : filter.from().toString(),
                        filter.to() == null ? "" : filter.to().toString(),
                        after.scheduledAt().toString(),
                        after.runId().toString()));
    }

    private Cursor decode(TeamContext team, String encoded) {
        List<String> values = cursors.decode(LIST, team.teamId(), encoded);
        if (values.size() != 7) throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        try {
            return new Cursor(
                    new Filter(
                            values.get(0).isEmpty() ? null : RunStatus.valueOf(values.get(0)),
                            values.get(1).isEmpty() ? null : UUID.fromString(values.get(1)),
                            emptyNull(values.get(2)),
                            values.get(3).isEmpty() ? null : Instant.parse(values.get(3)),
                            values.get(4).isEmpty() ? null : Instant.parse(values.get(4))),
                    new Key(Instant.parse(values.get(5)), UUID.fromString(values.get(6))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        }
    }

    private static Filter merge(
            Filter requested,
            Filter original,
            @Nullable String status,
            @Nullable String job,
            @Nullable String endpoint,
            @Nullable String from,
            @Nullable String to) {
        if ((status != null && !Objects.equals(requested.status(), original.status()))
                || (job != null && !Objects.equals(requested.jobId(), original.jobId()))
                || (endpoint != null && !Objects.equals(endpoint, original.endpoint()))
                || (from != null && !Objects.equals(requested.from(), original.from()))
                || (to != null && !Objects.equals(requested.to(), original.to()))) {
            throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        }
        return original;
    }

    private static @Nullable RunStatus parseStatus(@Nullable String value) {
        if (value == null) return null;
        try {
            RunStatus status = RunStatus.valueOf(value);
            if (status == RunStatus.UPSTREAM_FAILED) throw new IllegalArgumentException();
            return status;
        } catch (IllegalArgumentException e) {
            throw ValidationFailedException.of("status", "enum");
        }
    }

    private static @Nullable UUID parseUuid(String field, @Nullable String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ValidationFailedException.of(field, FieldError.FORMAT);
        }
    }

    private static @Nullable Instant parseTime(String field, @Nullable String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw ValidationFailedException.of(field, FieldError.FORMAT);
        }
    }

    private static @Nullable String emptyNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private record Cursor(Filter filter, Key after) {}

    /** Run list/cancel response. */
    public record RunResponse(
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
        static RunResponse of(RunSummary row) {
            return new RunResponse(
                    row.runId(),
                    row.jobId(),
                    row.workflowRunId(),
                    row.taskKey(),
                    row.status(),
                    row.attempt(),
                    row.scheduledAt(),
                    row.finishedAt(),
                    row.httpStatus(),
                    row.latencyMs());
        }
    }

    /** Detailed run response. */
    public record RunDetailsResponse(
            UUID runId,
            @Nullable UUID jobId,
            @Nullable UUID workflowRunId,
            @Nullable String taskKey,
            RunStatus status,
            int attempt,
            @Nullable String trigger,
            Instant scheduledAt,
            @Nullable Instant startedAt,
            @Nullable Instant finishedAt,
            @Nullable Integer httpStatus,
            @Nullable Integer latencyMs,
            @Nullable String errorClass,
            @Nullable String error,
            List<AttemptResponse> attempts) {
        static RunDetailsResponse of(RunDetails details) {
            RunSummary run = details.current();
            return new RunDetailsResponse(
                    run.runId(),
                    run.jobId(),
                    run.workflowRunId(),
                    run.taskKey(),
                    run.status(),
                    run.attempt(),
                    details.trigger(),
                    run.scheduledAt(),
                    details.startedAt(),
                    run.finishedAt(),
                    run.httpStatus(),
                    run.latencyMs(),
                    details.errorClass(),
                    details.error(),
                    details.attempts().stream().map(AttemptResponse::of).toList());
        }
    }

    /** Attempt response. */
    public record AttemptResponse(
            int attempt,
            @Nullable String outcome,
            @Nullable Integer httpStatus,
            @Nullable Integer latencyMs,
            Instant finishedAt) {
        static AttemptResponse of(Attempt row) {
            return new AttemptResponse(
                    row.attempt(), row.outcome(), row.httpStatus(), row.latencyMs(), row.finishedAt());
        }
    }
}
