package dev.kairos.api.audit.infrastructure;

import dev.kairos.api.audit.application.AuditStore;
import dev.kairos.domain.id.TeamId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Uses the team/time index and partition bounds; all values are named parameters. Thread-safe. */
public final class JdbcAuditStore implements AuditStore {
    private static final String LIST = """
            SELECT audit_id, at, actor, action, resource_type, resource_id, details::text AS details
              FROM scheduler.audit_log
             WHERE team_id = :team AND at >= :from AND at <= :to
            """;
    private static final TypeReference<Map<String, Object>> DETAILS = new TypeReference<>() {};
    private final JdbcClient control;
    private final JsonMapper json;

    public JdbcAuditStore(JdbcClient control, JsonMapper json) {
        this.control = control;
        this.json = json;
    }

    @Override
    public List<Event> list(TeamId team, Filter filter, @Nullable Key after, int limit) {
        String sql = LIST + (filter.action() == null ? "" : " AND action = :action")
                + (after == null ? "" : " AND (at, audit_id) < (:afterTime, :afterId)")
                + " ORDER BY at DESC, audit_id DESC LIMIT :limit";
        JdbcClient.StatementSpec query = control.sql(sql)
                .param("team", team.value())
                .param("from", filter.from().atOffset(ZoneOffset.UTC))
                .param("to", filter.to().atOffset(ZoneOffset.UTC))
                .param("limit", limit);
        if (filter.action() != null) {
            query = query.param("action", filter.action());
        }
        if (after != null) {
            query = query.param("afterTime", after.at().atOffset(ZoneOffset.UTC))
                    .param("afterId", after.id());
        }
        return query.query((rs, row) -> {
                    String details = rs.getString("details");
                    return new Event(
                            rs.getObject("audit_id", UUID.class),
                            rs.getObject("at", OffsetDateTime.class).toInstant(),
                            rs.getString("actor"),
                            rs.getString("action"),
                            rs.getString("resource_type"),
                            rs.getString("resource_id"),
                            details == null ? Map.of() : json.readValue(details, DETAILS));
                })
                .list();
    }
}
