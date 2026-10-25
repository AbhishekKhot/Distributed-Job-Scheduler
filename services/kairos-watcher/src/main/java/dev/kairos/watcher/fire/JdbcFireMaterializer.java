package dev.kairos.watcher.fire;

import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.schedule.Fire;
import dev.kairos.engine.run.CallSpec;
import dev.kairos.engine.run.PlannedRun;
import dev.kairos.engine.run.RunCreator;
import dev.kairos.engine.run.RunFactory;
import dev.kairos.engine.run.RunTarget;
import dev.kairos.engine.run.WorkflowRunCreator;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.proto.v1.NameValue;
import dev.kairos.proto.v1.RequestSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Materializes pinned job or workflow occurrences through the shared transactional run creator. */
public final class JdbcFireMaterializer implements FireMaterializer {
    private static final String JOB = """
            SELECT j.status, v.spec::text
              FROM scheduler.jobs j JOIN scheduler.job_versions v ON v.job_id = j.job_id
             WHERE j.job_id = :id AND j.team_id = :team AND v.version = :version
            """;
    private static final String WORKFLOW = """
            SELECT w.status, v.spec::text
              FROM scheduler.workflows w JOIN scheduler.workflow_versions v ON v.workflow_id = w.workflow_id
             WHERE w.workflow_id = :id AND w.team_id = :team AND v.version = :version
            """;
    private static final String ENDPOINT = """
            SELECT endpoint_id, version, spec::text
              FROM scheduler.api_endpoints
             WHERE team_id = :team AND name = :name AND status = 'ACTIVE'
            """;
    private static final Set<String> CREDENTIAL_HEADERS =
            Set.of("authorization", "proxy-authorization", "cookie", "set-cookie");
    private final JdbcClient control;
    private final JsonMapper json;
    private final KairosIdFactory ids;
    private final RunFactory factory;
    private final RunCreator creator;

    /** Connects the control endpoint registry to cell-local definition versions and runs. */
    public JdbcFireMaterializer(
            JdbcClient control, JsonMapper json, KairosIdFactory ids, RunFactory factory, RunCreator creator) {
        this.control = control;
        this.json = json;
        this.ids = ids;
        this.factory = factory;
        this.creator = creator;
    }

    @Override
    public int create(Cell cell, FireSchedule schedule, List<Fire> occurrences) {
        if (occurrences.isEmpty()) return 0;
        return switch (schedule.targetType()) {
            case JOB -> job(cell, schedule, occurrences);
            case WORKFLOW -> workflow(cell, schedule, occurrences);
            case WORKFLOW_TASK -> throw new IllegalArgumentException("A task cannot own a schedule");
        };
    }

    private int job(Cell cell, FireSchedule schedule, List<Fire> occurrences) {
        StoredDefinition definition = definition(cell, schedule, JOB);
        if (!"ACTIVE".equals(definition.status())) return 0;
        JsonNode spec = json.readTree(definition.spec());
        Endpoint endpoint = endpoint(schedule.teamId(), requiredText(spec, "endpoint"));
        RunTarget target = target(
                schedule,
                TargetType.JOB,
                schedule.id(),
                null,
                endpoint,
                spec.path("request"),
                spec.path("maxLatenessSec"));
        List<PlannedRun> planned = new ArrayList<>(occurrences.size());
        for (Fire occurrence : occurrences) {
            planned.add(factory.scheduled(target, occurrence.at(), Duration.ofMillis(schedule.spreadMillis())));
        }
        return creator.create(planned, null).created().size();
    }

    private int workflow(Cell cell, FireSchedule schedule, List<Fire> occurrences) {
        StoredDefinition definition = definition(cell, schedule, WORKFLOW);
        if (!"ACTIVE".equals(definition.status())) return 0;
        JsonNode spec = json.readTree(definition.spec());
        JsonNode tasks = spec.path("tasks");
        if (!tasks.isObject() || tasks.isEmpty()) throw new IllegalStateException("Stored workflow has no tasks");
        int count = 0;
        for (Fire occurrence : occurrences) {
            UUID workflowRun = ids.newWorkflowRunId(dev.kairos.domain.shard.Shard.of(schedule.shard()))
                    .value();
            List<WorkflowRunCreator.Task> planned = new ArrayList<>(tasks.size());
            for (Map.Entry<String, JsonNode> task : tasks.properties()) {
                JsonNode taskSpec = task.getValue();
                Endpoint endpoint = endpoint(schedule.teamId(), requiredText(taskSpec, "endpoint"));
                RunTarget target = target(
                        schedule,
                        TargetType.WORKFLOW_TASK,
                        workflowRun,
                        new TaskKey(task.getKey()),
                        endpoint,
                        taskSpec.path("request"),
                        taskSpec.path("maxLatenessSec"));
                JsonNode dependencies = taskSpec.path("dependsOn");
                int remaining = dependencies.isArray() ? dependencies.size() : 0;
                planned.add(new WorkflowRunCreator.Task(target, remaining));
            }
            if (!new WorkflowRunCreator(factory, creator)
                    .create(
                            cell,
                            new TeamId(schedule.teamId()),
                            schedule.id(),
                            schedule.definitionVersion(),
                            workflowRun,
                            occurrence.at(),
                            Trigger.SCHEDULE,
                            Duration.ofMillis(schedule.spreadMillis()),
                            planned)) continue;
            count++;
        }
        return count;
    }

    private StoredDefinition definition(Cell cell, FireSchedule schedule, String sql) {
        return cell.jdbc()
                .sql(sql)
                .param("id", schedule.id())
                .param("team", schedule.teamId())
                .param("version", schedule.definitionVersion())
                .query((rs, row) -> new StoredDefinition(rs.getString(1), rs.getString(2)))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Scheduled definition version is missing"));
    }

    private Endpoint endpoint(UUID team, String name) {
        return control.sql(ENDPOINT)
                .param("team", team)
                .param("name", name)
                .query((rs, row) -> new Endpoint(
                        rs.getObject("endpoint_id", UUID.class),
                        rs.getInt("version"),
                        json.readTree(rs.getString("spec"))))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Scheduled endpoint is unavailable"));
    }

    private RunTarget target(
            FireSchedule schedule,
            TargetType type,
            UUID targetId,
            TaskKey taskKey,
            Endpoint endpoint,
            JsonNode request,
            JsonNode maxLateness) {
        Duration limit = maxLateness.isNumber() ? Duration.ofSeconds(maxLateness.longValue()) : null;
        JsonNode timeout = endpoint.spec().path("timeoutMs");
        int timeoutMs = timeout.isNumber() ? timeout.intValue() : 30_000;
        return new RunTarget(
                new TeamId(schedule.teamId()),
                type,
                schedule.id(),
                schedule.definitionVersion(),
                targetId,
                taskKey,
                new EndpointId(endpoint.id()),
                endpoint.version(),
                new Priority(schedule.priority()),
                limit,
                new CallSpec(
                        timeoutMs,
                        type == TargetType.WORKFLOW_TASK
                                ? RequestSnapshot.getDefaultInstance()
                                : snapshot(request, endpoint.spec())));
    }

    private RequestSnapshot snapshot(JsonNode request, JsonNode endpoint) {
        RequestSnapshot.Builder snapshot = RequestSnapshot.newBuilder();
        JsonNode path = request.path("pathParams");
        if (path.isObject()) {
            for (Map.Entry<String, JsonNode> entry : path.properties()) {
                snapshot.putPathParams(entry.getKey(), entry.getValue().asString());
            }
        }
        addQuery(snapshot, request.path("query"));
        Map<String, NameValue> headers = new LinkedHashMap<>();
        mergeHeaders(headers, endpoint.path("defaultHeaders"));
        mergeHeaders(headers, request.path("headers"));
        snapshot.addAllHeaders(headers.values());
        JsonNode body = request.path("body");
        if (!body.isMissingNode()) {
            snapshot.setBody(com.google.protobuf.ByteString.copyFrom(json.writeValueAsBytes(body)));
        }
        JsonNode auth = endpoint.path("auth").path("secretRef");
        if (auth.isString()) snapshot.addSecretRefs(auth.asString());
        return snapshot.build();
    }

    private static void addQuery(RequestSnapshot.Builder snapshot, JsonNode values) {
        if (!values.isObject()) return;
        for (Map.Entry<String, JsonNode> entry : values.properties()) {
            snapshot.addQuery(NameValue.newBuilder()
                    .setName(entry.getKey())
                    .setValue(entry.getValue().asString())
                    .build());
        }
    }

    private static void mergeHeaders(Map<String, NameValue> headers, JsonNode values) {
        if (!values.isObject()) return;
        for (Map.Entry<String, JsonNode> entry : values.properties()) {
            String name = entry.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            // T-243 renders credential-bearing template fields from the pinned definition.
            // They never enter a dispatch snapshot, even as unresolved placeholders.
            if (CREDENTIAL_HEADERS.contains(lower)) continue;
            headers.put(
                    lower,
                    NameValue.newBuilder()
                            .setName(name)
                            .setValue(entry.getValue().asString())
                            .build());
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank())
            throw new IllegalStateException("Stored endpoint reference is missing");
        return value.asString();
    }

    private record StoredDefinition(String status, String spec) {}

    private record Endpoint(UUID id, int version, JsonNode spec) {}
}
