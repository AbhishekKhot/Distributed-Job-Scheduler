package dev.kairos.watcher.delayed;

import dev.kairos.domain.model.TargetType;
import dev.kairos.engine.run.CallSpec;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.proto.v1.NameValue;
import dev.kairos.proto.v1.RequestSnapshot;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Rebuilds a delayed run's dispatch call from its immutable pinned versions. */
public final class DelayedDispatchLoader implements DelayedDispatchSource {
    private static final String JOB = """
            SELECT spec::text FROM scheduler.job_versions
             WHERE job_id = :id AND version = :version
            """;
    private static final String WORKFLOW = """
            SELECT spec::text FROM scheduler.workflow_versions
             WHERE workflow_id = :id AND version = :version
            """;
    private static final String ENDPOINT = """
            SELECT endpoint.status, version.spec::text, team.quotas::text
              FROM scheduler.api_endpoints endpoint
              JOIN scheduler.api_endpoint_versions version ON version.endpoint_id = endpoint.endpoint_id
              JOIN scheduler.teams team ON team.team_id = endpoint.team_id
             WHERE endpoint.endpoint_id = :id AND endpoint.team_id = :team AND version.version = :version
            """;
    private static final Set<String> CREDENTIAL_HEADERS =
            Set.of("authorization", "proxy-authorization", "cookie", "set-cookie");

    private final JdbcClient control;
    private final JsonMapper json;

    /** Connects the cell definition store to the control endpoint registry. */
    public DelayedDispatchLoader(JdbcClient control, JsonMapper json) {
        this.control = Objects.requireNonNull(control, "control");
        this.json = Objects.requireNonNull(json, "json");
    }

    /** Returns the pinned call and current endpoint status of a claimed run. */
    @Override
    public LoadedDispatch load(Cell cell, DelayedRun delayed) {
        var run = delayed.run();
        String definitionSql = run.targetType() == TargetType.JOB ? JOB : WORKFLOW;
        JsonNode definition = cell.jdbc()
                .sql(definitionSql)
                .param("id", run.definitionId())
                .param("version", run.definitionVersion())
                .query(String.class)
                .optional()
                .map(json::readTree)
                .orElseThrow(() -> new IllegalStateException("Delayed run definition version is missing"));
        JsonNode request = definition.path("request");
        if (run.targetType() == TargetType.WORKFLOW_TASK) {
            request = definition.path("tasks").path(run.taskKeyColumn()).path("request");
        }
        JsonNode finalRequest = request;
        return control.sql(ENDPOINT)
                .param("id", run.endpointId().value())
                .param("team", run.teamId().value())
                .param("version", run.endpointVersion())
                .query((rs, row) -> {
                    JsonNode endpoint = json.readTree(rs.getString(2));
                    JsonNode timeout = endpoint.path("timeoutMs");
                    int timeoutMs = timeout.isNumber() ? timeout.intValue() : 30_000;
                    return new LoadedDispatch(
                            rs.getString(1),
                            new CallSpec(
                                    timeoutMs,
                                    run.targetType() == TargetType.WORKFLOW_TASK
                                            ? RequestSnapshot.getDefaultInstance()
                                            : snapshot(finalRequest, endpoint)),
                            maxExecPerSecond(json.readTree(rs.getString(3))));
                })
                .optional()
                .orElseThrow(() -> new IllegalStateException("Delayed run endpoint version is missing"));
    }

    private RequestSnapshot snapshot(JsonNode request, JsonNode endpoint) {
        RequestSnapshot.Builder snapshot = RequestSnapshot.newBuilder();
        JsonNode path = request.path("pathParams");
        if (path.isObject()) {
            for (Map.Entry<String, JsonNode> entry : path.properties()) {
                snapshot.putPathParams(entry.getKey(), entry.getValue().asString());
            }
        }
        addValues(snapshot, request.path("query"));
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

    private static void addValues(RequestSnapshot.Builder snapshot, JsonNode values) {
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
            if (CREDENTIAL_HEADERS.contains(lower)) continue;
            headers.put(
                    lower,
                    NameValue.newBuilder()
                            .setName(name)
                            .setValue(entry.getValue().asString())
                            .build());
        }
    }

    private static int maxExecPerSecond(JsonNode quotas) {
        JsonNode value = quotas.path("maxExecPerSec");
        return value.canConvertToInt() && value.intValue() > 0 ? value.intValue() : 2_000;
    }

    /** A pinned call plus the endpoint's current lifecycle state. */
    public record LoadedDispatch(String endpointStatus, CallSpec call, int maxExecPerSecond) {
        /** Validates the loaded values. */
        public LoadedDispatch {
            Objects.requireNonNull(endpointStatus, "endpointStatus");
            Objects.requireNonNull(call, "call");
            if (maxExecPerSecond < 1) throw new IllegalArgumentException("maxExecPerSecond must be positive");
        }

        /** Returns whether dispatch must remain delayed until the endpoint is active. */
        public boolean suspended() {
            return !endpointStatus.equals("ACTIVE");
        }
    }
}
