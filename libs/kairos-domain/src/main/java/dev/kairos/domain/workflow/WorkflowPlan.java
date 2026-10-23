package dev.kairos.domain.workflow;

import dev.kairos.domain.model.TaskKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable computed plan to store with a workflow version. Nodes within each level are sorted by key.
 * @param levels topological levels, roots at level zero
 * @param roots tasks whose dependency count is zero
 * @param remainingDependencies initial unfinished-parent counts
 * @param downstream direct dependents
 * @param ancestors transitive ancestors for placeholder authorization
 */
public record WorkflowPlan(
        List<List<TaskKey>> levels,
        List<TaskKey> roots,
        Map<TaskKey, Integer> remainingDependencies,
        Map<TaskKey, List<TaskKey>> downstream,
        Map<TaskKey, Set<TaskKey>> ancestors) {
    /** Deep-copies every collection; counters are initial values, never mutable execution state. */
    public WorkflowPlan {
        levels = levels.stream().map(List::copyOf).toList();
        roots = List.copyOf(roots);
        remainingDependencies = Map.copyOf(remainingDependencies);
        Map<TaskKey, List<TaskKey>> children = new LinkedHashMap<>();
        downstream.forEach((key, value) -> children.put(key, List.copyOf(value)));
        downstream = Map.copyOf(children);
        Map<TaskKey, Set<TaskKey>> parents = new LinkedHashMap<>();
        ancestors.forEach((key, value) -> parents.put(key, Set.copyOf(value)));
        ancestors = Map.copyOf(parents);
    }
}
