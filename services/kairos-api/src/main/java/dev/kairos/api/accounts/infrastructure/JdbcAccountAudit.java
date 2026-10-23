package dev.kairos.api.accounts.infrastructure;

import dev.kairos.api.accounts.application.AccountAudit;
import dev.kairos.api.audit.application.AuditWriter;

/** Adapts existing accounts events to the shared transactional audit writer. Thread-safe. */
public final class JdbcAccountAudit implements AccountAudit {
    private final AuditWriter writer;

    public JdbcAccountAudit(AuditWriter writer) {
        this.writer = writer;
    }

    @Override
    public void record(Entry entry) {
        writer.record(new AuditWriter.Entry(
                entry.team(),
                entry.actor(),
                entry.action(),
                "user",
                entry.user().value().toString(),
                entry.details()));
    }
}
