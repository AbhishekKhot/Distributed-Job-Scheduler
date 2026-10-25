package dev.kairos.state.settle.infrastructure;

import dev.kairos.persistence.cell.CellRouter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/** Idempotently transfers committed auto-pause audit intents without spanning databases. */
public final class SettlementAuditRelay {
    private static final System.Logger LOG = System.getLogger(SettlementAuditRelay.class.getName());
    private final CellRouter cells;
    private final JdbcClient control;

    public SettlementAuditRelay(CellRouter cells, JdbcClient control) {
        this.cells = cells;
        this.control = control;
    }

    @Scheduled(fixedDelayString = "${kairos.state.settle.audit-interval:1s}")
    public void scheduled() {
        try {
            drainOnce();
        } catch (RuntimeException failure) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Auto-pause audit transfer will retry: {0}",
                    failure.getClass().getSimpleName());
        }
    }

    public int drainOnce() {
        int copied = 0;
        for (var cell : cells.allCells())
            for (Audit audit : cell.jdbc()
                    .sql("SELECT * FROM scheduler.settlement_audits ORDER BY at,audit_id LIMIT 100")
                    .query((rs, row) -> new Audit(
                            rs.getObject("audit_id", UUID.class),
                            rs.getTimestamp("at").toInstant(),
                            rs.getObject("team_id", UUID.class),
                            rs.getObject("job_id", UUID.class),
                            rs.getInt("consecutive_dead")))
                    .list()) {
                control.sql("""
                    INSERT INTO scheduler.audit_log(audit_id,at,team_id,actor,action,resource_type,resource_id,details)
                    VALUES (:id,:at,:team,'system:auto-pause','JOB_AUTO_PAUSED','job',:job,jsonb_build_object('consecutiveDead',CAST(:dead AS integer)))
                    ON CONFLICT(at,audit_id) DO NOTHING
                    """)
                        .param("id", audit.id())
                        .param("at", OffsetDateTime.ofInstant(audit.at(), ZoneOffset.UTC))
                        .param("team", audit.team())
                        .param("job", audit.job().toString())
                        .param("dead", audit.consecutive())
                        .update();
                cell.jdbc()
                        .sql("DELETE FROM scheduler.settlement_audits WHERE audit_id=:id")
                        .param("id", audit.id())
                        .update();
                copied++;
            }
        return copied;
    }

    private record Audit(UUID id, Instant at, UUID team, UUID job, int consecutive) {}
}
