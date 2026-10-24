package dev.kairos.watcher.singleton;

import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Removes resolved and expired records in bounded batches across control and cell databases. */
public final class RetentionPurger {
    static final int BATCH_SIZE = 2_000;
    private static final Duration PAUSE = Duration.ofMillis(50);
    private static final String PURGE_SESSIONS = """
            WITH victims AS (
                SELECT session_id FROM scheduler.user_sessions WHERE expires_at <= :now
                 ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED
            )
            DELETE FROM scheduler.user_sessions session USING victims
             WHERE session.session_id = victims.session_id
            """;
    private static final String PURGE_CONTROL_IDEMPOTENCY = """
            WITH victims AS (
                SELECT team_id, idem_key FROM scheduler.api_idempotency WHERE created_at <= :cutoff
                 ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED
            )
            DELETE FROM scheduler.api_idempotency idem USING victims
             WHERE idem.team_id = victims.team_id AND idem.idem_key = victims.idem_key
            """;
    private static final String PURGE_CELL_IDEMPOTENCY = PURGE_CONTROL_IDEMPOTENCY;
    private static final String PURGE_DEAD_LETTERS = """
            WITH victims AS (
                SELECT dlq_id FROM scheduler.dead_letters
                 WHERE status IN (3, 4) AND resolved_at <= :cutoff
                 ORDER BY resolved_at, dlq_id LIMIT :limit FOR UPDATE SKIP LOCKED
            )
            DELETE FROM scheduler.dead_letters dlq USING victims WHERE dlq.dlq_id = victims.dlq_id
            """;
    private static final String PURGE_JOBS = """
            WITH victims AS MATERIALIZED (
                SELECT job_id FROM scheduler.jobs
                 WHERE status = 'DELETED' AND updated_at <= :cutoff
                 ORDER BY updated_at, job_id LIMIT :limit FOR UPDATE SKIP LOCKED
            ), health AS (
                DELETE FROM scheduler.job_health h USING victims v WHERE h.job_id = v.job_id RETURNING h.job_id
            ), schedules AS (
                DELETE FROM scheduler.schedules s USING victims v WHERE s.schedule_id = v.job_id RETURNING s.schedule_id
            )
            DELETE FROM scheduler.jobs j USING victims v WHERE j.job_id = v.job_id
            """;
    private static final String PURGE_WORKFLOWS = """
            WITH victims AS MATERIALIZED (
                SELECT workflow_id FROM scheduler.workflows
                 WHERE status = 'DELETED' AND updated_at <= :cutoff
                 ORDER BY updated_at, workflow_id LIMIT :limit FOR UPDATE SKIP LOCKED
            ), schedules AS (
                DELETE FROM scheduler.schedules s USING victims v WHERE s.schedule_id = v.workflow_id RETURNING s.schedule_id
            )
            DELETE FROM scheduler.workflows w USING victims v WHERE w.workflow_id = v.workflow_id
            """;

    private final CellRouter cells;
    private final JdbcClient control;
    private final Clock clock;
    private final MeterRegistry meters;

    /** Creates the purger. */
    public RetentionPurger(CellRouter cells, JdbcClient control, Clock clock, MeterRegistry meters) {
        this.cells = Objects.requireNonNull(cells, "cells");
        this.control = Objects.requireNonNull(control, "control");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    /** Runs one-hour retention policies; returns the number of rows removed. */
    public int poll() {
        Instant now = clock.instant();
        Instant sevenDaysAgo = now.minus(Duration.ofDays(7));
        Instant oneDayAgo = now.minus(Duration.ofHours(24));
        int total = 0;
        total += purge(control, PURGE_SESSIONS, "sessions", now);
        total += purge(control, PURGE_CONTROL_IDEMPOTENCY, "control_idempotency", oneDayAgo);
        for (Cell cell : cells.allCells()) {
            total += purge(cell.jdbc(), PURGE_CELL_IDEMPOTENCY, "cell_idempotency", oneDayAgo);
            total += purge(cell.jdbc(), PURGE_DEAD_LETTERS, "dead_letters", sevenDaysAgo);
            total += purge(cell.jdbc(), PURGE_JOBS, "jobs", sevenDaysAgo);
            total += purge(cell.jdbc(), PURGE_WORKFLOWS, "workflows", sevenDaysAgo);
        }
        return total;
    }

    private int purge(JdbcClient jdbc, String sql, String table, Instant cutoff) {
        int total = 0;
        while (true) {
            int deleted = jdbc.sql(sql)
                    .param("now", Timestamp.from(cutoff))
                    .param("cutoff", Timestamp.from(cutoff))
                    .param("limit", BATCH_SIZE)
                    .update();
            total += deleted;
            if (deleted < BATCH_SIZE) break;
            meters.counter("kairos_purger_batches_total", "table", table).increment();
            pause();
        }
        if (total > 0)
            meters.counter("kairos_purger_rows_total", "table", table).increment(total);
        return total;
    }

    private static void pause() {
        try {
            Thread.sleep(PAUSE.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Purger interrupted between batches", interrupted);
        }
    }
}
