package dev.kairos.api.jobs.web;

import dev.kairos.api.jobs.application.JobStore;
import dev.kairos.api.jobs.application.JobStore.BatchResult;
import dev.kairos.api.jobs.application.JobStore.Filter;
import dev.kairos.api.jobs.application.JobStore.Job;
import dev.kairos.api.jobs.application.JobStore.JobRun;
import dev.kairos.api.jobs.application.JobStore.Key;
import dev.kairos.api.jobs.application.JobStore.LastRun;
import dev.kairos.api.jobs.application.JobStore.RunFilter;
import dev.kairos.api.jobs.application.JobStore.RunKey;
import dev.kairos.api.jobs.application.JobsUseCase;
import dev.kairos.api.jobs.application.JobsUseCase.Backfill;
import dev.kairos.api.security.TeamContext;
import dev.kairos.api.support.etag.ETags;
import dev.kairos.api.support.etag.IfMatch;
import dev.kairos.api.support.idempotency.IdempotencyScope;
import dev.kairos.api.support.idempotency.Idempotent;
import dev.kairos.api.support.paging.CursorCodec;
import dev.kairos.api.support.paging.Page;
import dev.kairos.api.support.paging.PageLimit;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.JobId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Team-scoped job APIs 34–45. */
@RestController
@Tag(name = "Jobs", description = "Create, schedule and operate HTTP jobs.")
public class JobsController {
    private static final String LIST = "jobs";
    private final JobsUseCase jobs;
    private final CursorCodec cursors;

    public JobsController(JobsUseCase jobs, CursorCodec cursors) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    /** LLD API 34. */
    @PostMapping(
            path = "/v1/jobs",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Idempotent(store = IdempotencyScope.NAME_SHARD)
    @Operation(summary = "Create a job", description = "Creates a versioned job in the cell selected by its name.")
    public ResponseEntity<JobResponse> create(TeamContext team, @RequestBody JsonNode body) {
        Job created = jobs.create(team.teamId(), team.actor(), body);
        return ResponseEntity.created(URI.create("/v1/jobs/" + created.id()))
                .header(HttpHeaders.ETAG, ETags.of(created.version()))
                .body(JobResponse.of(created));
    }

    /** LLD API 35. */
    @PostMapping(
            path = "/v1/jobs:batch",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Idempotent(store = IdempotencyScope.CONTROL)
    @ResponseStatus(HttpStatus.MULTI_STATUS)
    @Operation(summary = "Create jobs in bulk", description = "Creates up to 1,000 jobs with per-item results.")
    public BatchResponse createBatch(TeamContext team, @RequestBody JsonNode body) {
        return new BatchResponse(jobs.createBatch(team.teamId(), team.actor(), body));
    }

    /** LLD API 36. */
    @GetMapping(path = "/v1/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List jobs", description = "Lists jobs owned by the caller's team across all cells.")
    public Page<JobResponse> list(
            TeamContext team,
            @RequestParam(name = "status", required = false) @Nullable String status,
            @RequestParam(name = "endpoint", required = false) @Nullable String endpoint,
            @RequestParam(name = "name", required = false) @Nullable String name,
            @RequestParam(name = "limit", required = false) @Nullable Integer limit,
            @RequestParam(name = "cursor", required = false) @Nullable String cursor) {
        Filter requested = new Filter(JobsUseCase.status(status), endpoint, name);
        Cursor decoded = cursor == null ? null : decodeJobs(team, cursor);
        Filter filter = decoded == null ? requested : merge(requested, decoded.filter(), status, endpoint, name);
        JobsUseCase.ListResult result =
                jobs.list(team.teamId(), filter, decoded == null ? null : decoded.after(), PageLimit.resolve(limit));
        String next = result.next() == null
                ? null
                : cursors.encode(
                        LIST,
                        team.teamId(),
                        List.of(
                                filter.status() == null ? "" : filter.status(),
                                filter.endpoint() == null ? "" : filter.endpoint(),
                                filter.namePrefix() == null ? "" : filter.namePrefix(),
                                result.next().createdAt().toString(),
                                result.next().id().toString()));
        return new Page<>(result.items().stream().map(JobResponse::of).toList(), next);
    }

    /** LLD API 37. */
    @GetMapping(path = "/v1/jobs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a job", description = "Returns one team-owned job and its current version.")
    public ResponseEntity<JobResponse> get(TeamContext team, @PathVariable String id) {
        Job job = jobs.get(team.teamId(), parseId(id));
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.of(job.version()))
                .body(JobResponse.of(job));
    }

    /** LLD API 38. */
    @PutMapping(
            path = "/v1/jobs/{id}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update a job", description = "Appends an immutable job version and recomputes its schedule.")
    public ResponseEntity<JobResponse> update(
            TeamContext team, @PathVariable String id, @IfMatch long expectedVersion, @RequestBody JsonNode body) {
        Job updated = jobs.update(team.teamId(), parseId(id), expectedVersion, body);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.of(updated.version()))
                .body(JobResponse.of(updated));
    }

    /** LLD API 39. */
    @DeleteMapping("/v1/jobs/{id}")
    @Operation(summary = "Delete a job", description = "Cancels outstanding work and makes the job name reusable.")
    public ResponseEntity<Void> delete(TeamContext team, @PathVariable String id) {
        jobs.delete(team.teamId(), parseId(id));
        return ResponseEntity.noContent().build();
    }

    /** LLD API 40. */
    @PostMapping(path = "/v1/jobs/{id}/pause", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Pause a job", description = "Stops future schedule fires while queued work continues.")
    public JobResponse pause(TeamContext team, @PathVariable String id) {
        return JobResponse.of(jobs.pause(team.teamId(), parseId(id)));
    }

    /** LLD API 41. */
    @PostMapping(path = "/v1/jobs/{id}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Resume a job", description = "Recomputes its next fire and resumes its schedule.")
    public JobResponse resume(TeamContext team, @PathVariable String id) {
        return JobResponse.of(jobs.resume(team.teamId(), parseId(id)));
    }

    /** LLD API 42. */
    @PostMapping(path = "/v1/jobs/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cancel a job", description = "Cancels outstanding work and keeps the job visible.")
    public JobResponse cancel(TeamContext team, @PathVariable String id) {
        return JobResponse.of(jobs.cancel(team.teamId(), parseId(id)));
    }

    /** LLD API 43. */
    @PostMapping(path = "/v1/jobs/{id}/runnow", produces = MediaType.APPLICATION_JSON_VALUE)
    @Idempotent(store = IdempotencyScope.PATH_ID_SHARD)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Run a job now", description = "Creates a manual run from the current job version.")
    public ResponseEntity<RunNowResponse> runNow(
            TeamContext team, @PathVariable String id, @RequestBody(required = false) @Nullable JsonNode body) {
        if (body != null && !body.isNull() && !body.isObject()) {
            throw ValidationFailedException.of("request", FieldError.FORMAT);
        }
        JsonNode override = body != null && body.has("request") ? body.get("request") : null;
        JobStore.RunResult result = jobs.runNow(team.teamId(), parseId(id), override);
        return ResponseEntity.accepted()
                .location(URI.create("/v1/runs/" + result.runId()))
                .body(new RunNowResponse(result.runId(), result.status()));
    }

    /** LLD API 44. */
    @PostMapping(
            path = "/v1/jobs/{id}/backfill",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Backfill a job",
            description = "Schedules past cron or interval occurrences at a bounded rate.")
    public BackfillResponse backfill(TeamContext team, @PathVariable String id, @RequestBody BackfillRequest body) {
        Backfill result = jobs.backfill(team.teamId(), parseId(id), body.from(), body.to(), body.dryRun());
        return new BackfillResponse(result.occurrences(), result.firstAt(), result.lastAt(), result.dryRun());
    }

    /** LLD API 45. */
    @GetMapping(path = "/v1/jobs/{id}/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List job runs", description = "Lists retained runs for one job, newest first.")
    public Page<JobRunResponse> runs(
            TeamContext team,
            @PathVariable String id,
            @RequestParam(name = "status", required = false) @Nullable String status,
            @RequestParam(name = "from", required = false) @Nullable String from,
            @RequestParam(name = "to", required = false) @Nullable String to,
            @RequestParam(name = "limit", required = false) @Nullable Integer limit,
            @RequestParam(name = "cursor", required = false) @Nullable String cursor) {
        UUID jobId = parseId(id);
        Instant fromAt = from == null ? null : JobsUseCase.parseInstant("from", from);
        Instant toAt = to == null ? null : JobsUseCase.parseInstant("to", to);
        jobs.validateRunWindow(fromAt, toAt);
        RunFilter requested = new RunFilter(JobsUseCase.runStatus(status), fromAt, toAt);
        CursorRuns decoded = cursor == null ? null : decodeRuns(team, jobId, cursor);
        RunFilter filter = decoded == null ? requested : mergeRuns(requested, decoded.filter(), status, from, to);
        JobsUseCase.ListResultRuns result = jobs.runs(
                team.teamId(), jobId, filter, decoded == null ? null : decoded.after(), PageLimit.resolve(limit));
        String next = result.next() == null
                ? null
                : cursors.encode(
                        "job-runs:" + jobId,
                        team.teamId(),
                        List.of(
                                filter.status() == null ? "" : filter.status(),
                                filter.from() == null ? "" : filter.from().toString(),
                                filter.to() == null ? "" : filter.to().toString(),
                                result.next().scheduledAt().toString(),
                                result.next().runId().toString()));
        return new Page<>(result.items().stream().map(JobRunResponse::of).toList(), next);
    }

    private Cursor decodeJobs(TeamContext team, String cursor) {
        List<String> values = cursors.decode(LIST, team.teamId(), cursor);
        if (values.size() != 5) throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        try {
            return new Cursor(
                    new Filter(emptyNull(values.get(0)), emptyNull(values.get(1)), emptyNull(values.get(2))),
                    new Key(Instant.parse(values.get(3)), UUID.fromString(values.get(4))));
        } catch (RuntimeException exception) {
            throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        }
    }

    private CursorRuns decodeRuns(TeamContext team, UUID job, String cursor) {
        List<String> values = cursors.decode("job-runs:" + job, team.teamId(), cursor);
        if (values.size() != 5) throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        try {
            return new CursorRuns(
                    new RunFilter(
                            emptyNull(values.get(0)),
                            values.get(1).isEmpty() ? null : Instant.parse(values.get(1)),
                            values.get(2).isEmpty() ? null : Instant.parse(values.get(2))),
                    new RunKey(Instant.parse(values.get(3)), UUID.fromString(values.get(4))));
        } catch (RuntimeException exception) {
            throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        }
    }

    private static Filter merge(
            Filter requested, Filter old, @Nullable String status, @Nullable String endpoint, @Nullable String name) {
        if ((status != null && !Objects.equals(requested.status(), old.status()))
                || (endpoint != null && !Objects.equals(endpoint, old.endpoint()))
                || (name != null && !Objects.equals(name, old.namePrefix())))
            throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        return old;
    }

    private static RunFilter mergeRuns(
            RunFilter requested, RunFilter old, @Nullable String status, @Nullable String from, @Nullable String to) {
        if ((status != null && !Objects.equals(requested.status(), old.status()))
                || (from != null && !Objects.equals(requested.from(), old.from()))
                || (to != null && !Objects.equals(requested.to(), old.to())))
            throw ValidationFailedException.of("cursor", FieldError.FORMAT);
        return old;
    }

    private static String emptyNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static UUID parseId(String text) {
        return JobId.parse(text).value();
    }

    private record Cursor(Filter filter, Key after) {}

    private record CursorRuns(RunFilter filter, RunKey after) {}

    public record BatchResponse(List<BatchResult> results) {
        public BatchResponse {
            results = List.copyOf(results);
        }
    }

    public record RunNowResponse(UUID runId, String status) {}

    public record BackfillRequest(String from, String to, boolean dryRun) {}

    public record BackfillResponse(
            int occurrences,
            @Nullable Instant firstAt,
            @Nullable Instant lastAt,
            boolean dryRun) {}

    public record JobResponse(
            UUID id,
            String name,
            JsonNode schedule,
            String endpoint,
            JsonNode request,
            int priority,
            @Nullable JsonNode retryPolicy,
            String misfirePolicy,
            int maxCatchup,
            @Nullable Integer maxLatenessSec,
            int autoPauseAfter,
            String status,
            long version,
            @Nullable Instant nextFireAt,
            @Nullable LastRun lastRun,
            Instant createdAt,
            Instant updatedAt) {
        static JobResponse of(Job job) {
            JsonNode spec = job.spec();
            JsonNode retry = spec.path("retryPolicy");
            return new JobResponse(
                    job.id(),
                    job.name(),
                    spec.path("schedule"),
                    job.endpoint(),
                    spec.path("request"),
                    job.priority(),
                    retry.isMissingNode() ? null : retry,
                    text(spec.path("misfirePolicy"), "RUN_LATEST"),
                    spec.path("maxCatchup").asInt(100),
                    nullableInt(spec.path("maxLatenessSec")),
                    spec.path("autoPauseAfter").asInt(3),
                    job.status(),
                    job.version(),
                    job.nextFireAt(),
                    job.lastRun(),
                    job.createdAt(),
                    job.updatedAt());
        }
    }

    public record JobRunResponse(
            UUID runId,
            UUID jobId,
            String status,
            int attempt,
            Instant scheduledAt,
            @Nullable Instant finishedAt,
            @Nullable Integer httpStatus,
            @Nullable Integer latencyMs) {
        static JobRunResponse of(JobRun run) {
            return new JobRunResponse(
                    run.runId(),
                    run.jobId(),
                    run.status(),
                    run.attempt(),
                    run.scheduledAt(),
                    run.finishedAt(),
                    run.httpStatus(),
                    run.latencyMs());
        }
    }

    private static String text(JsonNode node, String fallback) {
        return node.isString() ? node.asString() : fallback;
    }

    private static @Nullable Integer nullableInt(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.intValue();
    }
}
