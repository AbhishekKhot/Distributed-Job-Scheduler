package dev.kairos.api.jobs.application;

import dev.kairos.api.jobs.application.JobStore.BatchError;
import dev.kairos.api.jobs.application.JobStore.BatchResult;
import dev.kairos.api.jobs.application.JobStore.Filter;
import dev.kairos.api.jobs.application.JobStore.Job;
import dev.kairos.api.jobs.application.JobStore.JobRun;
import dev.kairos.api.jobs.application.JobStore.Key;
import dev.kairos.api.jobs.application.JobStore.NewJob;
import dev.kairos.api.jobs.application.JobStore.RunFilter;
import dev.kairos.api.jobs.application.JobStore.RunKey;
import dev.kairos.api.jobs.application.JobStore.Transition;
import dev.kairos.api.team.TeamProperties;
import dev.kairos.api.team.application.TeamQuotas;
import dev.kairos.api.team.application.TeamStore;
import dev.kairos.api.team.application.TeamUsage;
import dev.kairos.api.team.application.TeamUsageReader;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.KairosException;
import dev.kairos.domain.error.NotFoundException;
import dev.kairos.domain.error.QuotaExceededException;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.DstPolicy;
import dev.kairos.domain.schedule.Fire;
import dev.kairos.domain.schedule.ScheduleCalculator;
import dev.kairos.domain.schedule.ScheduleSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Job API use cases (LLD APIs 34–45). */
public final class JobsUseCase {
    public static final int MAX_BATCH_SIZE = 1_000;
    public static final int MAX_BACKFILL_OCCURRENCES = 10_000;
    private static final long RUN_HISTORY = 7L;

    private final JobStore store;
    private final JobCreationGuard creationGuard;
    private final JobEndpointCatalog endpoints;
    private final TeamStore teams;
    private final TeamUsageReader usage;
    private final TeamProperties teamProperties;
    private final KairosIdFactory ids;
    private final Clock clock;
    private final JsonMapper json;

    public JobsUseCase(
            JobStore store,
            JobCreationGuard creationGuard,
            JobEndpointCatalog endpoints,
            TeamStore teams,
            TeamUsageReader usage,
            TeamProperties teamProperties,
            KairosIdFactory ids,
            Clock clock,
            JsonMapper json) {
        this.store = Objects.requireNonNull(store, "store");
        this.creationGuard = Objects.requireNonNull(creationGuard, "creationGuard");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.teamProperties = Objects.requireNonNull(teamProperties, "teamProperties");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = Objects.requireNonNull(json, "json");
    }

    public Job create(TeamId team, String actor, JsonNode body) {
        JobStore.NewJob job = prepare(team, actor, body, 0);
        return creationGuard.execute(team, () -> {
            ensureJobQuota(team, 1);
            return store.create(
                    team, job.id(), actor, job.spec(), job.endpoint(), job.schedule(), job.now(), job.immediateCall());
        });
    }

    private NewJob prepare(TeamId team, String actor, JsonNode body, int index) {
        Instant now = now();
        JobSpec spec = JobSpec.parse(body, now, json);
        JobEndpointCatalog.Endpoint endpoint = endpoint(team, spec.endpoint());
        validateEndpoint(spec, endpoint);
        var id = ids.newJobId(dev.kairos.domain.shard.ShardKey.of(team.value(), spec.name()));
        JobSchedule schedule = JobSchedule.from(spec, now);
        if (spec.schedule() instanceof ScheduleSpec.Immediate) requireEndpointActive(endpoint);
        dev.kairos.engine.run.CallSpec immediateCall = spec.schedule() instanceof ScheduleSpec.Immediate
                ? JobCallBuilder.build(endpoint.spec(), spec.request(), json)
                : null;
        return new NewJob(index, id.value(), actor, spec, endpoint, schedule, now, immediateCall);
    }

    public List<BatchResult> createBatch(TeamId team, String actor, JsonNode body) {
        return creationGuard.execute(team, () -> createBatchGuarded(team, actor, body));
    }

    private List<BatchResult> createBatchGuarded(TeamId team, String actor, JsonNode body) {
        JsonNode jobs = body.path("jobs");
        if (!jobs.isArray() || jobs.isEmpty()) throw ValidationFailedException.of("jobs", FieldError.REQUIRED);
        if (jobs.size() > MAX_BATCH_SIZE) throw ValidationFailedException.of("jobs", "too-many");
        ensureJobQuota(team, jobs.size());
        List<BatchResult> results = new ArrayList<>(jobs.size());
        List<NewJob> prepared = new ArrayList<>(jobs.size());
        for (int index = 0; index < jobs.size(); index++) {
            try {
                prepared.add(prepare(team, actor, jobs.get(index), index));
            } catch (KairosException error) {
                results.add(new BatchResult(
                        index,
                        error.errorCode().status(),
                        null,
                        new BatchError("/errors/" + error.errorCode().slug(), error.detail())));
            }
        }
        results.addAll(store.createBatch(team, prepared));
        results.sort(java.util.Comparator.comparingInt(BatchResult::index));
        return List.copyOf(results);
    }

    public ListResult list(TeamId team, Filter filter, @Nullable Key after, int limit) {
        List<Job> rows = store.list(team, filter, after, limit + 1);
        boolean more = rows.size() > limit;
        List<Job> items = more ? rows.subList(0, limit) : rows;
        Key next = more ? new Key(items.getLast().createdAt(), items.getLast().id()) : null;
        return new ListResult(items, next);
    }

    public Job get(TeamId team, UUID id) {
        return store.find(team, id).orElseThrow(() -> notFound());
    }

    public Job update(TeamId team, UUID id, long expectedVersion, JsonNode body) {
        Job current = get(team, id);
        Instant now = now();
        JobSpec spec = JobSpec.parse(body, now, json);
        if (!current.name().equals(spec.name())) {
            throw new ValidationFailedException(ErrorCode.NAME_IMMUTABLE, "A job's name cannot be changed.", List.of());
        }
        JobEndpointCatalog.Endpoint endpoint = endpoint(team, spec.endpoint());
        validateEndpoint(spec, endpoint);
        return store.update(team, id, expectedVersion, spec, endpoint, JobSchedule.from(spec, now), now);
    }

    public Job delete(TeamId team, UUID id) {
        Job current = get(team, id);
        if ("DELETED".equals(current.status())) throw notFound();
        store.delete(team, id, now());
        return get(team, id);
    }

    public Job pause(TeamId team, UUID id) {
        Job current = get(team, id);
        requireStatus(current, List.of("ACTIVE"), "Only an ACTIVE job can be paused.");
        return store.transition(team, id, Transition.PAUSE, jobSchedule(current), null, now());
    }

    public Job resume(TeamId team, UUID id) {
        Job current = get(team, id);
        requireStatus(current, List.of("PAUSED", "AUTO_PAUSED"), "Only a PAUSED or AUTO_PAUSED job can be resumed.");
        JobSpec spec = parseStored(current.spec(), current.createdAt());
        JobEndpointCatalog.Endpoint endpoint = endpoint(team, spec.endpoint());
        return store.transition(team, id, Transition.RESUME, JobSchedule.from(spec, now()), endpoint, now());
    }

    public Job cancel(TeamId team, UUID id) {
        Job current = get(team, id);
        requireStatus(current, List.of("ACTIVE", "PAUSED", "AUTO_PAUSED"), "The job is already cancelled.");
        return store.transition(team, id, Transition.CANCEL, jobSchedule(current), null, now());
    }

    public JobStore.RunResult runNow(TeamId team, UUID id, @Nullable JsonNode override) {
        Job job = get(team, id);
        if ("CANCELLED".equals(job.status()) || "DELETED".equals(job.status())) {
            throw new ConflictException(ErrorCode.INVALID_STATE, "A cancelled job cannot be run.");
        }
        JobSpec spec = parseStored(job.spec(), job.createdAt());
        JobEndpointCatalog.Endpoint endpoint = endpoint(team, spec.endpoint());
        requireEndpointActive(endpoint);
        JobSpec.Request request = overrideRequest(spec.request(), override);
        JobSpec.validateEndpoint(
                request, endpoint.spec().urlTemplate(), endpoint.spec().defaultHeaders());
        TeamQuotas quotas = quotas(team);
        TeamUsage current = usage.usage(team, now());
        if (current.execPerSec() >= quotas.maxExecPerSec()) {
            throw new QuotaExceededException("The team is over its executions-per-second quota.");
        }
        return store.runNow(
                team,
                id,
                job.version(),
                spec,
                endpoint,
                request,
                JobCallBuilder.build(endpoint.spec(), request, json),
                now());
    }

    public Backfill backfill(TeamId team, UUID id, String fromText, String toText, boolean dryRun) {
        Job job = get(team, id);
        if ("DELETED".equals(job.status())) throw notFound();
        if ("CANCELLED".equals(job.status())) {
            throw new ConflictException(ErrorCode.INVALID_STATE, "A cancelled job cannot be backfilled.");
        }
        Instant from = parseInstant("from", fromText);
        Instant to = parseInstant("to", toText);
        if (from.isAfter(to)) throw ValidationFailedException.of("from", FieldError.RANGE);
        JobSpec spec = parseStored(job.spec(), job.createdAt());
        if (!(spec.schedule() instanceof ScheduleSpec.Cron || spec.schedule() instanceof ScheduleSpec.Interval)) {
            throw new ConflictException(ErrorCode.NOT_RECURRING, "Backfill applies only to CRON and INTERVAL jobs.");
        }
        ScheduleCalculator calculator = ScheduleCalculator.of(spec.schedule(), DstPolicy.DEFAULT, job.createdAt());
        Instant lowerBound = from.minusNanos(1);
        List<Instant> occurrences = new ArrayList<>();
        Optional<Fire> next = calculator.nextFire(lowerBound, null);
        while (next.isPresent() && !next.get().at().isAfter(to)) {
            occurrences.add(next.get().at());
            if (occurrences.size() > MAX_BACKFILL_OCCURRENCES) {
                throw new ValidationFailedException(
                        ErrorCode.RANGE_TOO_LARGE, "A backfill can create at most 10K occurrences.", List.of());
            }
            Fire fire = next.get();
            next = calculator.nextFire(fire.at(), fire.local());
        }
        if (dryRun) {
            return new Backfill(
                    occurrences.size(),
                    occurrences.isEmpty() ? null : occurrences.getFirst(),
                    occurrences.isEmpty() ? null : occurrences.getLast(),
                    true);
        }
        JobEndpointCatalog.Endpoint endpoint = endpoint(team, spec.endpoint());
        requireEndpointActive(endpoint);
        JobStore.BackfillResult result = store.backfill(
                team,
                id,
                job.version(),
                spec,
                endpoint,
                occurrences,
                JobCallBuilder.build(endpoint.spec(), spec.request(), json),
                Math.min(
                        dev.kairos.engine.run.RunFactory.DEFAULT_BACKFILL_RATE_PER_SECOND,
                        quotas(team).backfillPerSec()));
        return new Backfill(result.occurrences(), result.firstAt(), result.lastAt(), false);
    }

    public ListResultRuns runs(TeamId team, UUID id, RunFilter filter, @Nullable RunKey after, int limit) {
        get(team, id);
        List<JobRun> rows = store.runs(team, id, filter, after, limit + 1);
        boolean more = rows.size() > limit;
        List<JobRun> items = more ? rows.subList(0, limit) : rows;
        RunKey next =
                more ? new RunKey(items.getLast().scheduledAt(), items.getLast().runId()) : null;
        return new ListResultRuns(items, next);
    }

    public static @Nullable String status(String value) {
        if (value == null) return null;
        if (!List.of("ACTIVE", "PAUSED", "AUTO_PAUSED", "COMPLETED", "FAILED", "CANCELLED")
                .contains(value)) {
            throw ValidationFailedException.of("status", "enum");
        }
        return value;
    }

    public static @Nullable String runStatus(String value) {
        if (value == null) return null;
        if (!List.of("WAITING", "QUEUED", "RUNNING", "DELAYED", "SUCCEEDED", "DEAD_LETTERED", "CANCELLED", "SKIPPED")
                .contains(value)) throw ValidationFailedException.of("status", "enum");
        return value;
    }

    public static Instant parseInstant(String field, String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw ValidationFailedException.of(field, FieldError.FORMAT);
        }
    }

    public static void validateRunWindow(@Nullable Instant from, @Nullable Instant to, Instant now) {
        Instant oldest = now.minus(RUN_HISTORY, ChronoUnit.DAYS);
        if ((from != null && from.isBefore(oldest)) || (to != null && to.isBefore(oldest))) {
            throw ValidationFailedException.of("from", FieldError.RANGE);
        }
        if (from != null && to != null && from.isAfter(to))
            throw ValidationFailedException.of("from", FieldError.RANGE);
    }

    public void validateRunWindow(@Nullable Instant from, @Nullable Instant to) {
        validateRunWindow(from, to, now());
    }

    private JobEndpointCatalog.Endpoint endpoint(TeamId team, String name) {
        return endpoints
                .find(team, name)
                .orElseThrow(() -> new ValidationFailedException(
                        ErrorCode.ENDPOINT_NOT_FOUND, "The endpoint does not exist in this team.", List.of()));
    }

    private static void validateEndpoint(JobSpec job, JobEndpointCatalog.Endpoint endpoint) {
        job.validateEndpoint(endpoint.spec().urlTemplate(), endpoint.spec().defaultHeaders());
    }

    private static void requireEndpointActive(JobEndpointCatalog.Endpoint endpoint) {
        if (!"ACTIVE".equals(endpoint.status())) {
            throw new ConflictException(ErrorCode.INVALID_STATE, "The endpoint is not active.");
        }
    }

    private void ensureJobQuota(TeamId team, int requested) {
        TeamQuotas quotas = quotas(team);
        if (usage.usage(team, now()).jobs() + requested > quotas.maxJobs()) {
            throw new QuotaExceededException("The team has reached its limit of " + quotas.maxJobs() + " jobs.");
        }
    }

    private TeamQuotas quotas(TeamId team) {
        TeamQuotas defaults = teamProperties.defaultQuotas().toQuotas();
        return teams.findTeam(team)
                .map(value -> TeamQuotas.withDefaults(value.quotas(), defaults))
                .orElse(defaults);
    }

    private JobSpec parseStored(JsonNode spec, Instant createdAt) {
        return JobSpec.parse(spec, createdAt, json);
    }

    private JobSchedule jobSchedule(Job job) {
        return JobSchedule.from(parseStored(job.spec(), job.createdAt()), now());
    }

    private static JobSpec.Request overrideRequest(JobSpec.Request original, @Nullable JsonNode override) {
        if (override == null || override.isNull() || override.isMissingNode()) return original;
        if (!override.isObject()) throw ValidationFailedException.of("request", FieldError.FORMAT);
        JobSpec.Request parsed = JobSpec.request(override);
        return new JobSpec.Request(
                override.has("pathParams") ? merge(original.pathParams(), parsed.pathParams()) : original.pathParams(),
                override.has("query") ? merge(original.query(), parsed.query()) : original.query(),
                override.has("headers") ? merge(original.headers(), parsed.headers()) : original.headers(),
                override.has("body") ? parsed.body() : original.body());
    }

    private static java.util.Map<String, String> merge(
            java.util.Map<String, String> original, java.util.Map<String, String> replacement) {
        java.util.Map<String, String> merged = new java.util.LinkedHashMap<>(original);
        merged.putAll(replacement);
        return java.util.Map.copyOf(merged);
    }

    private static void requireStatus(Job job, List<String> expected, String detail) {
        if (!expected.contains(job.status())) throw new ConflictException(ErrorCode.INVALID_STATE, detail);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("The job does not exist.");
    }

    public record ListResult(List<Job> items, @Nullable Key next) {
        public ListResult {
            items = List.copyOf(items);
        }
    }

    public record ListResultRuns(
            List<JobRun> items, @Nullable RunKey next) {
        public ListResultRuns {
            items = List.copyOf(items);
        }
    }

    public record Backfill(
            int occurrences,
            @Nullable Instant firstAt,
            @Nullable Instant lastAt,
            boolean dryRun) {}
}
