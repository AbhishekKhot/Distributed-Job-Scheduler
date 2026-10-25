package dev.kairos.api.workflows.application;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.DstPolicy;
import dev.kairos.domain.model.EndpointName;
import dev.kairos.domain.model.MisfirePolicy;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.schedule.ScheduleCalculator;
import dev.kairos.domain.schedule.ScheduleRequest;
import dev.kairos.domain.schedule.ScheduleSpec;
import dev.kairos.domain.template.JsonValue;
import dev.kairos.domain.template.OutputSpec;
import dev.kairos.domain.template.RequestTemplate;
import dev.kairos.domain.workflow.WorkflowPlan;
import dev.kairos.domain.workflow.WorkflowSpec;
import dev.kairos.domain.workflow.WorkflowTask;
import dev.kairos.domain.workflow.WorkflowValidator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Validated workflow request and immutable trusted execution plan. */
public record WorkflowDefinition(
        String name,
        ScheduleSpec schedule,
        int priority,
        int spreadSec,
        MisfirePolicy misfirePolicy,
        int maxCatchup,
        Map<String, Task> tasks,
        WorkflowPlan plan,
        JsonNode serialized) {
    private static final Pattern PARAMETER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_-]{0,63})}");
    public static final int MAX_BYTES = 512 * 1024;

    public WorkflowDefinition {
        tasks = Map.copyOf(tasks);
    }

    public static WorkflowDefinition parse(
            JsonNode body,
            Instant now,
            WorkflowEndpointCatalog endpoints,
            dev.kairos.domain.id.TeamId team,
            int maxTasks,
            JsonMapper json) {
        if (!body.isObject()) invalid("", FieldError.FORMAT);
        if (json.writeValueAsBytes(body).length > MAX_BYTES) invalid("", "too-large");
        String name = text(body, "name");
        if (!name.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")) invalid("name", FieldError.PATTERN);
        JsonNode timing = body.path("schedule");
        if (!timing.isObject()) invalid("schedule", FieldError.REQUIRED);
        ScheduleSpec schedule = new ScheduleRequest(
                        text(timing, "type"),
                        optionalText(timing, "at"),
                        optionalText(timing, "expression"),
                        optionalText(timing, "timezone"),
                        optionalLong(timing, "everySec"))
                .toSpec(now);
        int priority = integer(body, "priority", 5);
        Priority.of(priority);
        int spread = integer(timing, "spreadSec", 0);
        if (spread < 0 || spread > 60) invalid("schedule.spreadSec", FieldError.RANGE);
        ScheduleCalculator.of(schedule, DstPolicy.DEFAULT, now).period().ifPresent(period -> {
            if (spread != 0 && (long) spread * 10000 >= period.toMillis())
                invalid("schedule.spreadSec", FieldError.RANGE);
        });
        MisfirePolicy misfire = enumeration(body, "misfirePolicy", MisfirePolicy.class, MisfirePolicy.DEFAULT);
        int catchup = integer(body, "maxCatchup", 100);
        if (catchup < 0 || catchup > 1000) invalid("maxCatchup", FieldError.RANGE);
        WorkflowSpec.FailurePolicy failure = enumeration(
                body, "onTaskFailure", WorkflowSpec.FailurePolicy.class, WorkflowSpec.FailurePolicy.BLOCK_FOR_REVIEW);
        int timeout = integer(body, "reviewTimeoutSec", 86400);
        if (timeout < 60 || timeout > 604800) invalid("reviewTimeoutSec", FieldError.RANGE);
        JsonNode taskNodes = body.path("tasks");
        if (!taskNodes.isObject() || taskNodes.isEmpty()) invalid("tasks", FieldError.REQUIRED);
        if (taskNodes.size() > WorkflowValidator.MAX_TASKS)
            throw new ValidationFailedException(
                    ErrorCode.TOO_MANY_TASKS, "A workflow has at most 200 tasks and 10 levels.", List.of());
        if (taskNodes.size() > maxTasks)
            throw new dev.kairos.domain.error.QuotaExceededException("The workflow exceeds the team's task quota.");
        List<WorkflowTask> graph = new ArrayList<>();
        List<WorkflowSpec.Edge> edges = new ArrayList<>();
        Map<String, Task> tasks = new LinkedHashMap<>();
        taskNodes.properties().forEach(entry -> {
            TaskKey key = TaskKey.of(entry.getKey());
            JsonNode node = entry.getValue();
            if (!node.isObject()) invalid("tasks." + key, FieldError.FORMAT);
            String endpointName = text(node, "endpoint");
            var endpoint = endpoints
                    .find(team, endpointName)
                    .orElseThrow(() -> new ValidationFailedException(
                            ErrorCode.ENDPOINT_NOT_FOUND, "The task endpoint does not exist in this team.", List.of()));
            RequestTemplate request = request(node.path("request"), json);
            java.util.Set<String> headerNames = new java.util.HashSet<>();
            request.headers().forEach((header, value) -> {
                if (!headerNames.add(header.toLowerCase(java.util.Locale.ROOT))
                        || value.chars().anyMatch(c -> c < 32 || c == 127))
                    invalid("request.headers", FieldError.FORMAT);
            });
            Set<String> required = new java.util.HashSet<>();
            var matcher = PARAMETER.matcher(endpoint.spec().urlTemplate());
            while (matcher.find()) required.add(matcher.group(1));
            if (!required.equals(request.pathParams().keySet()))
                invalid("tasks." + key + ".request.pathParams", FieldError.REQUIRED);
            List<String> parents = new ArrayList<>();
            JsonNode dependencies = node.path("dependsOn");
            if (!dependencies.isMissingNode() && !dependencies.isArray())
                invalid("tasks." + key + ".dependsOn", FieldError.FORMAT);
            if (dependencies.isArray())
                dependencies.forEach(parent -> {
                    if (!parent.isString()) invalid("tasks." + key + ".dependsOn", FieldError.FORMAT);
                    TaskKey parentKey = TaskKey.of(parent.asString());
                    parents.add(parentKey.value());
                    edges.add(new WorkflowSpec.Edge(parentKey, key));
                });
            Map<String, OutputSpec> outputs = new LinkedHashMap<>();
            endpoint.spec()
                    .outputs()
                    .forEach((output, value) -> outputs.put(
                            output,
                            new OutputSpec(
                                    OutputSpec.Source.valueOf(value.from().name()),
                                    value.path() == null ? "" : value.path(),
                                    value.required(),
                                    value.sensitive())));
            graph.add(new WorkflowTask(key, EndpointName.of(endpointName), request, outputs));
            Integer lateness = optionalInteger(node, "maxLatenessSec");
            if (lateness != null && (lateness < 0 || (long) lateness * 1000 > Integer.MAX_VALUE))
                invalid("maxLatenessSec", FieldError.RANGE);
            tasks.put(key.value(), new Task(key.value(), endpointName, List.copyOf(parents), request, lateness));
        });
        WorkflowPlan plan = WorkflowValidator.validate(new WorkflowSpec(graph, edges, failure));
        ObjectNode stored = (ObjectNode) body.deepCopy();
        stored.put("priority", priority)
                .put("onTaskFailure", failure.name())
                .put("reviewTimeoutSec", timeout)
                .put("misfirePolicy", misfire.name())
                .put("maxCatchup", catchup);
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("roots", plan.roots().stream().map(TaskKey::value).toList());
        encoded.put(
                "levels",
                plan.levels().stream()
                        .map(level -> level.stream().map(TaskKey::value).toList())
                        .toList());
        Map<String, Integer> remaining = new LinkedHashMap<>();
        plan.remainingDependencies().forEach((key, value) -> remaining.put(key.value(), value));
        encoded.put("remainingDependencies", remaining);
        Map<String, List<String>> downstream = new LinkedHashMap<>();
        plan.downstream()
                .forEach((key, value) -> downstream.put(
                        key.value(), value.stream().map(TaskKey::value).toList()));
        encoded.put("downstream", downstream);
        Map<String, List<String>> ancestors = new LinkedHashMap<>();
        plan.ancestors()
                .forEach((key, value) -> ancestors.put(
                        key.value(), value.stream().map(TaskKey::value).sorted().toList()));
        encoded.put("ancestors", ancestors);
        stored.set("plan", json.valueToTree(encoded));
        if (json.writeValueAsBytes(stored).length > MAX_BYTES) invalid("", "too-large");
        return new WorkflowDefinition(name, schedule, priority, spread, misfire, catchup, tasks, plan, stored);
    }

    public static RequestTemplate request(JsonNode node, JsonMapper json) {
        if (node.isMissingNode() || node.isNull()) return RequestTemplate.empty();
        if (!node.isObject()) invalid("request", FieldError.FORMAT);
        return new RequestTemplate(
                stringMap(node.path("pathParams")),
                stringMap(node.path("query")),
                stringMap(node.path("headers")),
                node.has("body") ? JsonValue.of(json.convertValue(node.get("body"), Object.class)) : null);
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return Map.of();
        if (!node.isObject()) invalid("request", FieldError.FORMAT);
        Map<String, String> values = new LinkedHashMap<>();
        node.properties().forEach(e -> {
            if (!e.getValue().isString()) invalid("request", FieldError.FORMAT);
            values.put(e.getKey(), e.getValue().asString());
        });
        return values;
    }

    public static String text(JsonNode node, String key) {
        String value = optionalText(node, key);
        if (value == null || value.isBlank()) {
            invalid(key, FieldError.REQUIRED);
            return "";
        }
        return value;
    }

    private static @Nullable String optionalText(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString()) invalid(key, FieldError.FORMAT);
        return value.asString();
    }

    private static @Nullable Long optionalLong(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.canConvertToLong()) invalid(key, FieldError.FORMAT);
        return value.longValue();
    }

    private static @Nullable Integer optionalInteger(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.canConvertToInt()) invalid(key, FieldError.FORMAT);
        return value.intValue();
    }

    private static int integer(JsonNode node, String key, int fallback) {
        Integer value = optionalInteger(node, key);
        return value == null ? fallback : value;
    }

    private static <E extends Enum<E>> E enumeration(JsonNode node, String key, Class<E> type, E fallback) {
        String value = optionalText(node, key);
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            invalid(key, "enum");
            throw e;
        }
    }

    private static void invalid(String field, String code) {
        throw ValidationFailedException.of(field, code);
    }

    public record Task(
            String key,
            String endpoint,
            List<String> dependsOn,
            RequestTemplate request,
            @Nullable Integer maxLatenessSec) {}
}
