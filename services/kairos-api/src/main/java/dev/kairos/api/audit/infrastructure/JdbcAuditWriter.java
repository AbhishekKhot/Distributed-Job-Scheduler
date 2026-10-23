package dev.kairos.api.audit.infrastructure;

import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.domain.id.KairosIdFactory;
import java.sql.Types;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Shared audit insert on the control client; joins the caller's transaction. Thread-safe. */
public final class JdbcAuditWriter implements AuditWriter {
    private static final String INSERT = """
            INSERT INTO scheduler.audit_log (audit_id, at, team_id, actor, action, resource_type, resource_id, details)
            VALUES (:id, :at, :team, :actor, :action, :type, :resource, CAST(:details AS jsonb))
            """;
    private final JdbcClient control;
    private final KairosIdFactory ids;
    private final Clock clock;
    private final JsonMapper json;

    public JdbcAuditWriter(JdbcClient control, KairosIdFactory ids, Clock clock, JsonMapper json) {
        this.control = control;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public void record(Entry entry) {
        control.sql(INSERT)
                .param("id", ids.newId())
                .param("at", clock.instant().atOffset(ZoneOffset.UTC))
                .param("team", entry.team() == null ? null : entry.team().value(), Types.OTHER)
                .param("actor", entry.actor())
                .param("action", entry.action())
                .param("type", entry.resourceType(), Types.VARCHAR)
                .param("resource", entry.resourceId(), Types.VARCHAR)
                .param("details", json.writeValueAsString(new TreeMap<>(entry.details())))
                .update();
    }
}
