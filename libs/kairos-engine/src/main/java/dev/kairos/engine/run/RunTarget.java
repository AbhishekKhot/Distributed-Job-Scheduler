package dev.kairos.engine.run;

import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.shard.Shard;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a new run executes, with the versions pinned when it is created: the job (or workflow task), the definition
 * and endpoint versions, the priority, the lateness limit and the call.
 *
 * <p>A job run has {@code definitionId == targetId} (the job id) and no task key. A workflow task has the workflow id
 * as definition, the workflow run id as target and its task key. Both ids carry the same shard, which every run of the
 * target inherits (LLD section 1).
 *
 * @param teamId the owning team
 * @param targetType {@link TargetType#JOB} or {@link TargetType#WORKFLOW_TASK}
 * @param definitionId the job or workflow id
 * @param definitionVersion the pinned job or workflow version, at least 1
 * @param targetId the job id, or the workflow run id of a task
 * @param taskKey the workflow task key; null for jobs
 * @param endpointId the endpoint to call
 * @param endpointVersion the pinned endpoint version, at least 1
 * @param priority the dispatch priority
 * @param maxLateness skip the run if it would start later than this after its due time; null for no limit
 * @param call the call timeout and rendered request
 */
public record RunTarget(
        TeamId teamId,
        TargetType targetType,
        UUID definitionId,
        int definitionVersion,
        UUID targetId,
        @Nullable TaskKey taskKey,
        EndpointId endpointId,
        int endpointVersion,
        Priority priority,
        @Nullable Duration maxLateness,
        CallSpec call) {

    /**
     * Validates the target.
     *
     * @throws IllegalArgumentException if the target type is not a run type, the task key does not match it, a job's
     *     definition and target differ, the ids are not Kairos ids of one shard, a version is below 1, or the lateness
     *     limit is negative or longer than {@code Integer.MAX_VALUE} ms
     */
    public RunTarget {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(call, "call");
        RunRules.requireRunTarget(targetType, definitionId, targetId, taskKey);
        RunRules.requireVersion("definitionVersion", definitionVersion);
        RunRules.requireVersion("endpointVersion", endpointVersion);
        if (maxLateness != null && (maxLateness.isNegative() || maxLateness.toMillis() > Integer.MAX_VALUE)) {
            throw new IllegalArgumentException("The lateness limit must be 0 to " + Integer.MAX_VALUE + " ms");
        }
    }

    /**
     * Returns the shard every run of this target belongs to.
     *
     * @return the shard embedded in the target id
     */
    public Shard shard() {
        return KairosId.shardOf(targetId);
    }

    /**
     * Returns the lateness limit as stored in {@code run_inflight.max_lateness_ms}.
     *
     * @return the limit in milliseconds, or null for no limit
     */
    public @Nullable Integer maxLatenessMs() {
        return maxLateness == null ? null : Math.toIntExact(maxLateness.toMillis());
    }
}
