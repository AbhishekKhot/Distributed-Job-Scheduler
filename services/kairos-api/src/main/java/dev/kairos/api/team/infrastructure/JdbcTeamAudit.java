package dev.kairos.api.team.infrastructure;

import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.team.application.TeamAudit;

/** Adapts existing team events to the shared transactional audit writer. Thread-safe. */
public final class JdbcTeamAudit implements TeamAudit {
    private final AuditWriter writer;

    public JdbcTeamAudit(AuditWriter writer) {
        this.writer = writer;
    }

    @Override
    public void record(Entry entry) {
        writer.record(new AuditWriter.Entry(
                entry.team(),
                entry.actor(),
                entry.action(),
                entry.resourceType(),
                entry.resourceId(),
                entry.details()));
    }
}
