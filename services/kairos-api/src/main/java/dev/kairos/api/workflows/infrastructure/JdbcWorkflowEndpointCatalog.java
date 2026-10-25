package dev.kairos.api.workflows.infrastructure;

import dev.kairos.api.endpoints.application.EndpointSpec;
import dev.kairos.api.workflows.application.WorkflowEndpointCatalog;
import dev.kairos.domain.id.TeamId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Loads current endpoint names and retained immutable endpoint versions for a team. */
public final class JdbcWorkflowEndpointCatalog implements WorkflowEndpointCatalog {
    private final JdbcClient control;
    private final JsonMapper json;

    public JdbcWorkflowEndpointCatalog(JdbcClient control, JsonMapper json) {
        this.control = control;
        this.json = json;
    }

    @Override
    public Optional<Endpoint> find(TeamId team, String name) {
        return control.sql(
                        "SELECT endpoint_id,name,status,version,spec::text FROM scheduler.api_endpoints WHERE team_id=:team AND name=:name")
                .param("team", team.value())
                .param("name", name)
                .query((rs, row) -> new Endpoint(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4),
                        json.readValue(rs.getString(5), EndpointSpec.class)))
                .optional();
    }

    @Override
    public Endpoint pinned(TeamId team, UUID id, int version) {
        return control.sql("""
                SELECT e.endpoint_id,e.name,e.status,v.version,v.spec::text FROM scheduler.api_endpoints e
                JOIN scheduler.api_endpoint_versions v ON v.endpoint_id=e.endpoint_id
                WHERE e.team_id=:team AND e.endpoint_id=:id AND v.version=:version
                """)
                .param("team", team.value())
                .param("id", id)
                .param("version", version)
                .query((rs, row) -> new Endpoint(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4),
                        json.readValue(rs.getString(5), EndpointSpec.class)))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Pinned workflow endpoint is missing"));
    }
}
