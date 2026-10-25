package dev.kairos.state.settle.infrastructure;

import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.retry.RetryPolicy;
import dev.kairos.domain.retry.RetryStrategy;
import dev.kairos.domain.retry.StatusOverride;
import dev.kairos.domain.retry.StatusRules;
import dev.kairos.domain.retry.UnknownOutcomePolicy;
import dev.kairos.engine.run.InflightRun;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.state.settle.application.RunPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Resolves whole-policy precedence and pinned endpoint status overrides, cached within one cell group. */
public final class SettlementPolicyLoader {
    private final JdbcClient control;
    private final JsonMapper json;

    public SettlementPolicyLoader(JdbcClient control, JsonMapper json) {
        this.control = control;
        this.json = json;
    }

    public Session session(Cell cell) {
        return new Session(cell);
    }

    public final class Session {
        private final Cell cell;
        private final Map<DefinitionKey, JsonNode> definitions = new HashMap<>();
        private final Map<EndpointKey, EndpointPolicy> endpoints = new HashMap<>();

        private Session(Cell cell) {
            this.cell = cell;
        }

        public RunPolicy load(InflightRun run) {
            RetryPolicy job = null;
            if (run.targetType() == TargetType.JOB) {
                JsonNode definition = definitions.computeIfAbsent(
                        new DefinitionKey(run.definitionId(), run.definitionVersion()),
                        key -> json.readTree(cell.jdbc()
                                .sql(
                                        "SELECT spec::text FROM scheduler.job_versions WHERE job_id=:id AND version=:version")
                                .param("id", key.id())
                                .param("version", key.version())
                                .query(String.class)
                                .single()));
                job = policy(definition.path("retryPolicy"));
            }
            EndpointPolicy endpoint = endpoints.computeIfAbsent(
                    new EndpointKey(run.teamId().value(), run.endpointId().value(), run.endpointVersion()),
                    key -> control.sql("""
                            SELECT v.spec::text,t.retry_default::text,t.completed_events_enabled
                            FROM scheduler.api_endpoint_versions v JOIN scheduler.api_endpoints e ON e.endpoint_id=v.endpoint_id
                            JOIN scheduler.teams t ON t.team_id=e.team_id
                            WHERE e.team_id=:team AND v.endpoint_id=:endpoint AND v.version=:version
                            """)
                            .param("team", key.team())
                            .param("endpoint", key.id())
                            .param("version", key.version())
                            .query((rs, row) -> new EndpointPolicy(
                                    json.readTree(rs.getString(1)),
                                    rs.getString(2) == null ? null : policy(json.readTree(rs.getString(2))),
                                    rs.getBoolean(3)))
                            .single());
            List<String> success = new ArrayList<>();
            endpoint.spec().path("successStatus").forEach(value -> success.add(value.asString()));
            if (success.isEmpty()) success.add("2xx");
            Map<String, StatusOverride> overrides = new LinkedHashMap<>();
            endpoint.spec()
                    .path("statusOverrides")
                    .properties()
                    .forEach(entry -> overrides.put(
                            entry.getKey(),
                            StatusOverride.valueOf(entry.getValue().asString())));
            return new RunPolicy(
                    RetryPolicy.resolve(
                            job,
                            policy(endpoint.spec().path("retryPolicy")),
                            endpoint.team(),
                            RetryPolicy.PLATFORM_DEFAULT),
                    UnknownOutcomePolicy.valueOf(
                            endpoint.spec().path("unknownOutcomePolicy").asString("RETRY_WITH_IDEMPOTENCY_KEY")),
                    StatusRules.parse(success, overrides),
                    endpoint.completed());
        }
    }

    private static @Nullable RetryPolicy policy(JsonNode node) {
        if (!node.isObject()) return null;
        return new RetryPolicy(
                node.path("maxRetries").asInt(),
                RetryStrategy.valueOf(node.path("strategy").asString()),
                node.path("baseDelaySec").asInt(),
                node.path("maxDelaySec").asInt(),
                node.path("maxAgeSec").asInt());
    }

    private record DefinitionKey(UUID id, int version) {}

    private record EndpointKey(UUID team, UUID id, int version) {}

    private record EndpointPolicy(JsonNode spec, @Nullable RetryPolicy team, boolean completed) {}
}
