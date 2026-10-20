package dev.kairos.engine.run;

import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.model.TaskKey;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Checks shared by the run records; they mirror the {@code run_inflight} constraints of the cell schema. */
final class RunRules {

    /** Largest value of a {@code smallint} counter column. */
    static final int MAX_SMALLINT = Short.MAX_VALUE;

    private RunRules() {}

    static void requireRunTarget(TargetType targetType, UUID definitionId, UUID targetId, @Nullable TaskKey taskKey) {
        KairosId.requireKairosId(definitionId);
        KairosId.requireKairosId(targetId);
        switch (targetType) {
            case JOB -> {
                if (taskKey != null) {
                    throw new IllegalArgumentException("A job run has no task key");
                }
                if (!definitionId.equals(targetId)) {
                    throw new IllegalArgumentException("A job run targets its own job");
                }
            }
            case WORKFLOW_TASK -> {
                if (taskKey == null) {
                    throw new IllegalArgumentException("A workflow task run needs its task key");
                }
            }
            case WORKFLOW -> throw new IllegalArgumentException("A run is a job or a workflow task, never a workflow");
        }
        // Runs inherit the shard of their job or workflow (LLD section 1), and so does a workflow run id.
        if (!KairosId.shardOf(definitionId).equals(KairosId.shardOf(targetId))) {
            throw new IllegalArgumentException("The definition and target ids belong to different shards");
        }
    }

    static void requireVersion(String field, int version) {
        if (version < 1) {
            throw new IllegalArgumentException(field + " must be at least 1");
        }
    }
}
