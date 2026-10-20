package dev.kairos.engine.run;

import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.RunState;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.run.Fence;
import dev.kairos.domain.run.RunStatus;
import dev.kairos.domain.shard.Shard;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A {@code run_inflight} row (LLD section 2.3) as it is created or dispatched.
 *
 * <p>The columns that only an executor or a sweeper changes ({@code deferrals}, {@code owner_node},
 * {@code lease_until}, {@code cancel_requested}) are not part of the record; a new row starts with their defaults.
 * The run's shard is the one embedded in its id, its definition id and its target id, which must agree.
 *
 * <p>Times are truncated to microseconds, the precision of {@code timestamptz}, so the occurrence key
 * ({@code target_id, task_key, scheduled_at}) is the same in memory and in the database.
 *
 * @param runId the run id, also the {@code Idempotency-Key} sent to the target
 * @param teamId the owning team
 * @param targetType {@link TargetType#JOB} or {@link TargetType#WORKFLOW_TASK}
 * @param definitionId the job or workflow id
 * @param definitionVersion the pinned definition version
 * @param targetId the job id, or the workflow run id of a task
 * @param taskKey the workflow task key; null for jobs (stored as the empty string)
 * @param endpointId the endpoint to call
 * @param endpointVersion the pinned endpoint version
 * @param scheduledAt the occurrence time
 * @param dueAt when the run is to be dispatched
 * @param state the state
 * @param attempt the attempt number, starting at 1
 * @param seriesStart the first attempt of the current retry series
 * @param seriesStartedAt when the current retry series started (the max-age anchor)
 * @param infraAttempt re-queues after executor loss for this attempt, 0 to 3
 * @param fence the fence; a run created {@code QUEUED} starts at 1
 * @param remainingDeps unfinished upstream tasks; positive exactly when the run is {@code WAITING}
 * @param priority the dispatch priority
 * @param trigger what created the run
 * @param maxLatenessMs skip the run if it would start later than this after its due time; null for no limit
 */
public record InflightRun(
        RunId runId,
        TeamId teamId,
        TargetType targetType,
        UUID definitionId,
        int definitionVersion,
        UUID targetId,
        @Nullable TaskKey taskKey,
        EndpointId endpointId,
        int endpointVersion,
        Instant scheduledAt,
        Instant dueAt,
        RunState state,
        int attempt,
        int seriesStart,
        Instant seriesStartedAt,
        int infraAttempt,
        Fence fence,
        int remainingDeps,
        Priority priority,
        Trigger trigger,
        @Nullable Integer maxLatenessMs) {

    /** Re-queues after executor loss allowed per attempt ({@code run_inflight_infra_attempt_ck}). */
    public static final int MAX_INFRA_ATTEMPTS = 3;

    /**
     * Validates the row against the {@code run_inflight} constraints.
     *
     * @throws IllegalArgumentException if the ids disagree on the shard or the target type, a version or counter is out
     *     of range, the retry series does not start between 1 and the attempt, or {@code remainingDeps} is positive
     *     for a run that is not {@code WAITING} (or zero for one that is)
     */
    public InflightRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(seriesStartedAt, "seriesStartedAt");
        Objects.requireNonNull(fence, "fence");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(trigger, "trigger");
        RunRules.requireRunTarget(targetType, definitionId, targetId, taskKey);
        if (!runId.shard().equals(KairosId.shardOf(targetId))) {
            throw new IllegalArgumentException("A run belongs to the shard of its target");
        }
        RunRules.requireVersion("definitionVersion", definitionVersion);
        RunRules.requireVersion("endpointVersion", endpointVersion);
        if (attempt < 1 || attempt > RunRules.MAX_SMALLINT) {
            throw new IllegalArgumentException("The attempt must be 1 to " + RunRules.MAX_SMALLINT);
        }
        if (seriesStart < 1 || seriesStart > attempt) {
            throw new IllegalArgumentException("A retry series starts between attempt 1 and the current attempt");
        }
        if (infraAttempt < 0 || infraAttempt > MAX_INFRA_ATTEMPTS) {
            throw new IllegalArgumentException("The infrastructure attempt must be 0 to " + MAX_INFRA_ATTEMPTS);
        }
        if (remainingDeps < 0 || remainingDeps > RunRules.MAX_SMALLINT) {
            throw new IllegalArgumentException("remainingDeps must be 0 to " + RunRules.MAX_SMALLINT);
        }
        if ((state == RunState.WAITING) != (remainingDeps > 0)) {
            // A WAITING run without upstream work would never move, and a dispatched run cannot wait for any.
            throw new IllegalArgumentException("Only a WAITING run has unfinished upstream tasks, and it has some");
        }
        if (maxLatenessMs != null && maxLatenessMs < 0) {
            throw new IllegalArgumentException("The lateness limit must not be negative");
        }
        scheduledAt = scheduledAt.truncatedTo(ChronoUnit.MICROS);
        dueAt = dueAt.truncatedTo(ChronoUnit.MICROS);
        seriesStartedAt = seriesStartedAt.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Returns the shard of the run.
     *
     * @return the shard embedded in the run id
     */
    public Shard shard() {
        return runId.shard();
    }

    /**
     * Returns the run status this row represents.
     *
     * @return the status of the row's state
     */
    public RunStatus status() {
        return RunStatus.of(state);
    }

    /**
     * Returns the task key as stored in {@code run_inflight.task_key}.
     *
     * @return the key, or the empty string for a job
     */
    public String taskKeyColumn() {
        return taskKey == null ? "" : taskKey.value();
    }

    /**
     * Returns a copy moved to {@code QUEUED} with a new fence and due time, as a dispatch stores it.
     *
     * @param newFence the fence after the dispatch
     * @param newDueAt the dispatch time
     * @return the queued row
     */
    InflightRun queued(Fence newFence, Instant newDueAt) {
        return new InflightRun(
                runId,
                teamId,
                targetType,
                definitionId,
                definitionVersion,
                targetId,
                taskKey,
                endpointId,
                endpointVersion,
                scheduledAt,
                newDueAt,
                RunState.QUEUED,
                attempt,
                seriesStart,
                seriesStartedAt,
                infraAttempt,
                newFence,
                0,
                priority,
                trigger,
                maxLatenessMs);
    }
}
