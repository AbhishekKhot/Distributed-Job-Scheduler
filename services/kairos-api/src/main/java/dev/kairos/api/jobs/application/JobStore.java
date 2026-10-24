package dev.kairos.api.jobs.application;

import dev.kairos.api.jobs.application.JobEndpointCatalog.Endpoint;
import dev.kairos.domain.id.TeamId;
import dev.kairos.engine.run.CallSpec;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Cell persistence port for team-scoped jobs and the runs they create. */
public interface JobStore {
    Optional<Job> find(TeamId team, UUID id);

    List<Job> list(TeamId team, Filter filter, @Nullable Key after, int limit);

    Job create(
            TeamId team,
            UUID id,
            String actor,
            JobSpec spec,
            JobEndpointCatalog.Endpoint endpoint,
            JobSchedule schedule,
            Instant now,
            @Nullable CallSpec immediateCall);

    List<BatchResult> createBatch(TeamId team, List<NewJob> jobs);

    record NewJob(
            int index,
            UUID id,
            String actor,
            JobSpec spec,
            Endpoint endpoint,
            JobSchedule schedule,
            Instant now,
            @Nullable CallSpec immediateCall) {}

    record BatchError(String type, String detail) {}

    record BatchResult(
            int index,
            int status,
            @Nullable UUID id,
            @Nullable BatchError error) {}

    Job update(
            TeamId team,
            UUID id,
            long expectedVersion,
            JobSpec spec,
            JobEndpointCatalog.Endpoint endpoint,
            JobSchedule schedule,
            Instant now);

    Job transition(
            TeamId team,
            UUID id,
            Transition transition,
            JobSchedule schedule,
            @Nullable Endpoint endpoint,
            Instant now);

    void delete(TeamId team, UUID id, Instant now);

    RunResult runNow(
            TeamId team,
            UUID id,
            long expectedVersion,
            JobSpec spec,
            JobEndpointCatalog.Endpoint endpoint,
            JobSpec.Request request,
            CallSpec call,
            Instant now);

    BackfillResult backfill(
            TeamId team,
            UUID id,
            long expectedVersion,
            JobSpec spec,
            JobEndpointCatalog.Endpoint endpoint,
            List<Instant> occurrences,
            CallSpec call,
            int ratePerSecond);

    List<JobRun> runs(TeamId team, UUID id, RunFilter filter, @Nullable RunKey after, int limit);

    record Job(
            UUID id,
            String name,
            String status,
            String endpoint,
            int priority,
            long version,
            JsonNode spec,
            @Nullable Instant nextFireAt,
            @Nullable LastRun lastRun,
            Instant createdAt,
            Instant updatedAt) {}

    record LastRun(UUID runId, String status, @Nullable Instant finishedAt) {}

    record Filter(
            @Nullable String status,
            @Nullable String endpoint,
            @Nullable String namePrefix) {}

    record Key(Instant createdAt, UUID id) {}

    enum Transition {
        PAUSE,
        RESUME,
        CANCEL
    }

    record RunResult(UUID runId, String status) {}

    record BackfillResult(
            int occurrences,
            @Nullable Instant firstAt,
            @Nullable Instant lastAt) {}

    record RunFilter(
            @Nullable String status,
            @Nullable Instant from,
            @Nullable Instant to) {}

    record RunKey(Instant scheduledAt, UUID runId) {}

    record JobRun(
            UUID runId,
            UUID jobId,
            String status,
            int attempt,
            Instant scheduledAt,
            @Nullable Instant finishedAt,
            @Nullable Integer httpStatus,
            @Nullable Integer latencyMs) {}
}
