package dev.kairos.executor.call.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.TeamId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Loads immutable, team-scoped endpoint versions and caches them by endpoint id and version. */
public final class JdbcEndpointVersions {

    private static final String SELECT = """
            SELECT version.spec::text
              FROM scheduler.api_endpoint_versions version
              JOIN scheduler.api_endpoints endpoint ON endpoint.endpoint_id = version.endpoint_id
             WHERE endpoint.team_id = :team AND endpoint.endpoint_id = :endpoint AND version.version = :version
            """;
    private static final String CURRENT = """
            SELECT endpoint.version
              FROM scheduler.api_endpoints endpoint
             WHERE endpoint.team_id = :team AND endpoint.endpoint_id = :endpoint
               AND endpoint.status = 'ACTIVE'
            """;

    private final JdbcClient control;
    private final JsonMapper json;
    private final EndpointVersionParser parser;
    private final Cache<Key, Optional<EndpointVersion>> cache =
            Caffeine.newBuilder().maximumSize(20_000).build();

    /** Creates the team-scoped endpoint version loader. */
    public JdbcEndpointVersions(JdbcClient control, JsonMapper json) {
        this.control = Objects.requireNonNull(control, "control");
        this.json = Objects.requireNonNull(json, "json");
        this.parser = new EndpointVersionParser();
    }

    /** Returns exactly the immutable endpoint version pinned in a dispatch. */
    public Optional<EndpointVersion> find(TeamId team, EndpointId endpoint, int version) {
        if (version < 1) return Optional.empty();
        Key key = new Key(team.value(), endpoint.value(), version);
        return cache.get(key, this::load);
    }

    /** Returns the active endpoint's current immutable version for an interactive test call. */
    public Optional<EndpointVersion> findCurrent(TeamId team, EndpointId endpoint) {
        return currentVersion(team, endpoint).flatMap(value -> find(team, endpoint, value));
    }

    /** Returns the active endpoint's current immutable version number. */
    public Optional<Integer> currentVersion(TeamId team, EndpointId endpoint) {
        return control.sql(CURRENT)
                .param("team", team.value())
                .param("endpoint", endpoint.value())
                .query(Integer.class)
                .optional();
    }

    private Optional<EndpointVersion> load(Key key) {
        Optional<String> spec = control.sql(SELECT)
                .param("team", key.team())
                .param("endpoint", key.endpoint())
                .param("version", key.version())
                .query(String.class)
                .optional();
        if (spec.isEmpty()) return Optional.empty();
        try {
            return Optional.of(parser.parse(json.readTree(spec.orElseThrow())));
        } catch (Exception failure) {
            throw new IllegalStateException("The pinned endpoint configuration is invalid", failure);
        }
    }

    private record Key(java.util.UUID team, java.util.UUID endpoint, int version) {
        private Key {
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(endpoint, "endpoint");
        }
    }
}
