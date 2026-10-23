package dev.kairos.domain.workflow;

import dev.kairos.domain.model.TaskKey;
import java.util.List;
import java.util.Objects;

/**
 * Workflow graph input. A list preserves duplicate keys for validation before the API converts its task map.
 * @param tasks task definitions
 * @param edges dependency edges, producer to consumer
 * @param onTaskFailure final-failure behavior
 */
public record WorkflowSpec(List<WorkflowTask> tasks, List<Edge> edges, FailurePolicy onTaskFailure) {
    /** Makes the input immutable. */
    public WorkflowSpec {
        tasks = List.copyOf(tasks);
        edges = List.copyOf(edges);
        Objects.requireNonNull(onTaskFailure, "onTaskFailure");
    }
    /**
     * Creates a graph with the default BLOCK_FOR_REVIEW policy.
     * @param tasks tasks
     * @param edges dependencies
     */
    public WorkflowSpec(List<WorkflowTask> tasks, List<Edge> edges) {
        this(tasks, edges, FailurePolicy.BLOCK_FOR_REVIEW);
    }

    /** Behavior after a final task failure. */
    public enum FailurePolicy {
        /** Pause the workflow for manual review. */
        BLOCK_FOR_REVIEW,
        /** Mark dependent tasks upstream-failed immediately. */
        FAIL_FAST
    }
    /**
     * Dependency direction: from must succeed before to can run.
     * @param from producer
     * @param to consumer
     */
    public record Edge(TaskKey from, TaskKey to) {
        /** Requires valid task keys. */
        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }
}
