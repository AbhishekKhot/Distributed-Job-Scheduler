package dev.kairos.watcher.recovery;

import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.retry.RetryPolicy;
import dev.kairos.domain.retry.RetryStrategy;
import dev.kairos.domain.retry.UnknownOutcomePolicy;
import dev.kairos.engine.run.InflightRun;
import dev.kairos.persistence.cell.Cell;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Resolves recovery policy from the run's pinned definition and endpoint versions. */
public final class RecoveryPolicyLoader {
    private static final String JOB = """
            SELECT spec::text FROM scheduler.job_versions WHERE job_id = :id AND version = :version
            """;
    private static final String ENDPOINT = """
            SELECT version.spec::text, team.retry_default::text
              FROM scheduler.api_endpoint_versions version
              JOIN scheduler.api_endpoints endpoint ON endpoint.endpoint_id = version.endpoint_id
              JOIN scheduler.teams team ON team.team_id = endpoint.team_id
             WHERE version.endpoint_id = :id AND version.version = :version AND team.team_id = :team
            """;

    private final JdbcClient control;
    private final JsonMapper json;

    /** Creates the loader. */
    public RecoveryPolicyLoader(JdbcClient control, JsonMapper json) {
        this.control = Objects.requireNonNull(control, "control");
        this.json = Objects.requireNonNull(json, "json");
    }

    /** Returns the resolved retry policy and endpoint unknown-outcome choice. */
    public Policy load(Cell cell, InflightRun run) {
        RetryPolicy job = null;
        if (run.targetType() == TargetType.JOB) {
            String spec = cell.jdbc()
                    .sql(JOB)
                    .param("id", run.definitionId())
                    .param("version", run.definitionVersion())
                    .query(String.class)
                    .single();
            job = policy(json.readTree(spec).path("retryPolicy"));
        }
        RetryPolicy jobPolicy = job;
        return control.sql(ENDPOINT)
                .param("id", run.endpointId().value())
                .param("version", run.endpointVersion())
                .param("team", run.teamId().value())
                .query((rs, row) -> {
                    JsonNode endpoint = json.readTree(rs.getString(1));
                    RetryPolicy team = rs.getString(2) == null ? null : policy(json.readTree(rs.getString(2)));
                    RetryPolicy resolved = RetryPolicy.resolve(
                            jobPolicy, policy(endpoint.path("retryPolicy")), team, RetryPolicy.PLATFORM_DEFAULT);
                    JsonNode choice = endpoint.path("unknownOutcomePolicy");
                    UnknownOutcomePolicy unknown = choice.isString()
                            ? UnknownOutcomePolicy.valueOf(choice.asString())
                            : UnknownOutcomePolicy.DEFAULT;
                    return new Policy(resolved, unknown);
                })
                .single();
    }

    private static @Nullable RetryPolicy policy(JsonNode node) {
        if (!node.isObject()) return null;
        JsonNode strategy = node.path("strategy");
        return new RetryPolicy(
                node.path("maxRetries").asInt(),
                RetryStrategy.valueOf(strategy.asString()),
                node.path("baseDelaySec").asInt(),
                node.path("maxDelaySec").asInt(),
                node.path("maxAgeSec").asInt());
    }

    /** Policy choices for a pinned run. */
    public record Policy(RetryPolicy retry, UnknownOutcomePolicy unknown) {}
}
