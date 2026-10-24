package dev.kairos.api.jobs.infrastructure;

import dev.kairos.api.endpoints.application.EndpointSpec;
import dev.kairos.api.jobs.application.JobEndpointCatalog;
import dev.kairos.domain.id.TeamId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Reads team-scoped endpoint names and current versions from the control database. */
public final class JdbcJobEndpointCatalog implements JobEndpointCatalog {
    private static final String FIND = """
            SELECT endpoint_id, name, status, version, spec::text
              FROM scheduler.api_endpoints
             WHERE team_id = :team AND name = :name
            """;

    private final JdbcClient control;
    private final JsonMapper json;

    public JdbcJobEndpointCatalog(@Qualifier("controlJdbcClient") JdbcClient control, JsonMapper json) {
        this.control = Objects.requireNonNull(control, "control");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public Optional<Endpoint> find(TeamId team, String name) {
        return control.sql(FIND)
                .param("team", team.value())
                .param("name", name)
                .query((rs, row) -> new Endpoint(
                        rs.getObject("endpoint_id", java.util.UUID.class),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getLong("version"),
                        json.readValue(rs.getString("spec"), EndpointSpec.class)))
                .optional();
    }
}
