package dev.kairos.api.accounts.infrastructure;

import dev.kairos.api.accounts.application.AccountAudit;
import dev.kairos.domain.id.KairosIdFactory;
import java.time.Clock;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AccountAudit} on {@code audit_log} in the control database (LLD section 2.2). The row joins the caller's
 * transaction when there is one, so a change and its entry commit together. Thread-safe.
 */
public final class JdbcAccountAudit implements AccountAudit {

    private static final String INSERT = """
            INSERT INTO scheduler.audit_log
                (audit_id, at, team_id, actor, action, resource_type, resource_id, details)
            VALUES (:auditId, :at, :teamId, :actor, :action, 'user', :resourceId, CAST(:details AS jsonb))
            """;

    private final JdbcClient control;
    private final KairosIdFactory ids;
    private final Clock clock;
    private final JsonMapper json;

    /**
     * Creates the writer.
     *
     * @param control the control database's client
     * @param ids creates audit ids
     * @param clock the time source
     * @param json serialises the details
     */
    public JdbcAccountAudit(JdbcClient control, KairosIdFactory ids, Clock clock, JsonMapper json) {
        this.control = Objects.requireNonNull(control, "control");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public void record(Entry entry) {
        control.sql(INSERT)
                .param("auditId", ids.newId())
                .param("at", JdbcUserAccounts.utc(clock.instant()))
                .param("teamId", entry.team() == null ? null : entry.team().value(), java.sql.Types.OTHER)
                .param("actor", entry.actor())
                .param("action", entry.action())
                .param("resourceId", entry.user().value().toString())
                // Sorted, so the stored JSON is stable for the same details.
                .param("details", json.writeValueAsString(new TreeMap<>(entry.details())))
                .update();
    }
}
