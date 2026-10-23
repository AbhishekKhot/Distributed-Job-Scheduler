package dev.kairos.domain.workflow;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.template.OutputSpec;
import dev.kairos.domain.template.RequestTemplate;
import dev.kairos.domain.template.TemplateEngine;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Deterministic Kahn traversal, explicit cycle paths, bounded graph size and ancestor-only placeholder validation. */
public final class WorkflowValidator {
    /** Largest workflow graph. */
    public static final int MAX_TASKS = 200;
    /** Largest number of topological levels. */
    public static final int MAX_LEVELS = 10;

    private static final Comparator<TaskKey> ORDER = Comparator.comparing(TaskKey::value);

    private WorkflowValidator() {}

    /**
     * Computes a plan and checks every template using resolved endpoint outputs. Multiple independent roots are valid.
     * @param spec graph and request values
     * @return an immutable plan
     * @throws ValidationFailedException for duplicate or unknown keys, cycles, excessive size, or invalid references
     */
    public static WorkflowPlan validate(WorkflowSpec spec) {
        if (spec.tasks().size() > MAX_TASKS) throw tooLarge();
        if (spec.tasks().isEmpty()) throw ValidationFailedException.of("tasks", FieldError.REQUIRED);
        Map<TaskKey, WorkflowTask> tasks = new TreeMap<>(ORDER);
        long templateBytes = 0;
        for (WorkflowTask task : spec.tasks()) {
            if (tasks.put(task.key(), task) != null) throw ValidationFailedException.of("tasks", "duplicate-key");
            templateBytes += size(task.request());
            if (templateBytes > 512 * 1024) throw ValidationFailedException.of("tasks", FieldError.LENGTH);
        }
        Map<TaskKey, Set<TaskKey>> children = new TreeMap<>(ORDER);
        Map<TaskKey, Integer> initial = new LinkedHashMap<>();
        Map<TaskKey, Set<TaskKey>> ancestors = new LinkedHashMap<>();
        for (TaskKey key : tasks.keySet()) {
            children.put(key, new TreeSet<>(ORDER));
            initial.put(key, 0);
            ancestors.put(key, new HashSet<>());
        }
        if (spec.edges().size() > MAX_TASKS * (MAX_TASKS - 1)) throw tooLarge();
        for (WorkflowSpec.Edge edge : spec.edges()) {
            if (!tasks.containsKey(edge.from()) || !tasks.containsKey(edge.to()))
                throw ValidationFailedException.of("tasks.dependsOn", "unknown-task");
            if (!Objects.requireNonNull(children.get(edge.from())).add(edge.to()))
                throw ValidationFailedException.of("tasks.dependsOn", "duplicate-edge");
            initial.put(edge.to(), Objects.requireNonNull(initial.get(edge.to())) + 1);
        }
        Map<TaskKey, Integer> remaining = new LinkedHashMap<>(initial);
        List<TaskKey> ready = tasks.keySet().stream()
                .filter(key -> Objects.requireNonNull(remaining.get(key)) == 0)
                .toList();
        List<TaskKey> roots = List.copyOf(ready);
        List<List<TaskKey>> levels = new ArrayList<>();
        int visited = 0;
        while (!ready.isEmpty()) {
            levels.add(ready);
            Set<TaskKey> next = new TreeSet<>(ORDER);
            for (TaskKey key : ready) {
                visited++;
                for (TaskKey child : Objects.requireNonNull(children.get(key))) {
                    Set<TaskKey> inherited = Objects.requireNonNull(ancestors.get(child));
                    inherited.addAll(Objects.requireNonNull(ancestors.get(key)));
                    inherited.add(key);
                    int count = Objects.requireNonNull(remaining.get(child)) - 1;
                    remaining.put(child, count);
                    if (count == 0) next.add(child);
                }
            }
            ready = List.copyOf(next);
        }
        if (visited != tasks.size())
            throw cycle(spec.tasks().stream().map(WorkflowTask::key).toList(), children);
        if (levels.size() > MAX_LEVELS) throw tooLarge();
        Map<TaskKey, Map<String, OutputSpec>> outputs = new LinkedHashMap<>();
        tasks.forEach((key, task) -> outputs.put(key, task.outputs()));
        for (WorkflowTask task : tasks.values()) {
            TemplateEngine.validateRequest(
                    task.request(), task.key(), Objects.requireNonNull(ancestors.get(task.key())), outputs);
        }
        Map<TaskKey, List<TaskKey>> downstream = new LinkedHashMap<>();
        children.forEach((key, value) -> downstream.put(key, List.copyOf(value)));
        return new WorkflowPlan(levels, roots, initial, downstream, ancestors);
    }

    private static long size(RequestTemplate request) {
        long bytes = 0;
        for (Map<String, String> fields : List.of(request.pathParams(), request.query(), request.headers())) {
            for (var field : fields.entrySet())
                bytes += field.getKey().getBytes(StandardCharsets.UTF_8).length
                        + field.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        var body = request.body();
        return bytes + (body == null ? 0 : body.json().getBytes(StandardCharsets.UTF_8).length);
    }

    private static ValidationFailedException cycle(List<TaskKey> tasks, Map<TaskKey, Set<TaskKey>> children) {
        Map<TaskKey, Integer> colors = new HashMap<>();
        List<TaskKey> path = new ArrayList<>();
        for (TaskKey task : tasks) {
            List<TaskKey> found = findCycle(task, children, colors, path);
            if (!found.isEmpty()) {
                String text =
                        String.join(" -> ", found.stream().map(TaskKey::value).toList());
                return new ValidationFailedException(
                        ErrorCode.CYCLE_DETECTED, "Tasks " + text + " form a cycle.", List.of());
            }
        }
        throw new IllegalStateException("Kahn traversal failed without a cycle");
    }

    private static List<TaskKey> findCycle(
            TaskKey task, Map<TaskKey, Set<TaskKey>> children, Map<TaskKey, Integer> colors, List<TaskKey> path) {
        int color = colors.getOrDefault(task, 0);
        if (color == 2) return List.of();
        if (color == 1) {
            List<TaskKey> result = new ArrayList<>(path.subList(path.indexOf(task), path.size()));
            result.add(task);
            return result;
        }
        colors.put(task, 1);
        path.add(task);
        for (TaskKey child : Objects.requireNonNull(children.get(task))) {
            List<TaskKey> result = findCycle(child, children, colors, path);
            if (!result.isEmpty()) return result;
        }
        path.removeLast();
        colors.put(task, 2);
        return List.of();
    }

    private static ValidationFailedException tooLarge() {
        return new ValidationFailedException(
                ErrorCode.TOO_MANY_TASKS, "A workflow has at most 200 tasks and 10 levels.", List.of());
    }
}
