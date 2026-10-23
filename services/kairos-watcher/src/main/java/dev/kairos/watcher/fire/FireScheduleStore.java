package dev.kairos.watcher.fire;

import dev.kairos.domain.model.DstPolicy;
import dev.kairos.domain.model.MisfirePolicy;
import dev.kairos.domain.model.ScheduleKind;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.schedule.FirePlan;
import dev.kairos.persistence.cell.Cell;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Claims bounded due schedules and advances them in the caller's lease-guarded cell transaction. */
public final class FireScheduleStore {
    private static final String CLAIM = """
            SELECT schedule_id, team_id, target_type, shard_id, kind, next_fire_at, cron_expr, timezone,
                   interval_ms, spread_ms, dst_policy, misfire_policy, max_catchup, definition_version,
                   priority, last_fired_local
              FROM scheduler.schedules
             WHERE shard_id = :shard AND status = 1 AND next_fire_at <= :horizon
               AND ((target_type = 1 AND EXISTS (
                    SELECT 1 FROM scheduler.jobs j WHERE j.job_id = schedule_id AND j.status = 'ACTIVE'))
                 OR (target_type = 3 AND EXISTS (
                    SELECT 1 FROM scheduler.workflows w WHERE w.workflow_id = schedule_id AND w.status = 'ACTIVE')))
             ORDER BY next_fire_at, schedule_id
             LIMIT 250 FOR UPDATE SKIP LOCKED
            """;
    private static final String ADVANCE = """
            UPDATE scheduler.schedules
               SET next_fire_at = :next, last_fired_local = :local
             WHERE schedule_id = :id AND shard_id = :shard AND status = 1
            """;
    private static final String DELETE = """
            DELETE FROM scheduler.schedules
             WHERE schedule_id = :id AND shard_id = :shard AND status = 1
            """;
    private static final String OLDEST_DUE = """
            SELECT min(created_at) FROM scheduler.outbox WHERE available_at <= :now
            """;

    /** Claims no more than 250 active schedules for one owned shard. */
    public List<FireSchedule> claim(Cell cell, int shard, Instant horizon) {
        Objects.requireNonNull(cell, "cell");
        return cell.jdbc()
                .sql(CLAIM)
                .param("shard", shard)
                .param("horizon", Timestamp.from(horizon))
                .query((rs, row) -> new FireSchedule(
                        rs.getObject("schedule_id", java.util.UUID.class),
                        rs.getObject("team_id", java.util.UUID.class),
                        TargetType.fromCode(rs.getInt("target_type")),
                        rs.getInt("shard_id"),
                        ScheduleKind.fromCode(rs.getInt("kind")),
                        rs.getTimestamp("next_fire_at").toInstant(),
                        rs.getString("cron_expr"),
                        rs.getString("timezone"),
                        rs.getObject("interval_ms", Long.class),
                        rs.getInt("spread_ms"),
                        DstPolicy.fromCode(rs.getInt("dst_policy")),
                        MisfirePolicy.fromCode(rs.getInt("misfire_policy")),
                        rs.getInt("max_catchup"),
                        rs.getInt("definition_version"),
                        rs.getInt("priority"),
                        rs.getObject("last_fired_local", java.time.LocalDateTime.class)))
                .list();
    }

    /** Returns the creation time of the oldest dispatch awaiting publication. */
    public java.util.Optional<Instant> oldestDue(Cell cell, Instant now) {
        JdbcClient jdbc = cell.jdbc();
        return jdbc.sql(OLDEST_DUE)
                .param("now", Timestamp.from(now))
                .query((rs, row) -> {
                    Timestamp value = rs.getTimestamp(1);
                    return value == null ? null : value.toInstant();
                })
                .optional();
    }

    /** Saves the new position, deleting one-time schedules after their sole occurrence. */
    public void advance(Cell cell, FireSchedule schedule, FirePlan plan) {
        int changed;
        if (plan.next() == null) {
            changed = cell.jdbc()
                    .sql(DELETE)
                    .param("id", schedule.id())
                    .param("shard", schedule.shard())
                    .update();
        } else {
            changed = cell.jdbc()
                    .sql(ADVANCE)
                    .param("id", schedule.id())
                    .param("shard", schedule.shard())
                    .param("next", Timestamp.from(plan.next().at()))
                    .param("local", plan.lastFiredLocal())
                    .update();
        }
        if (changed != 1) throw new IllegalStateException("Claimed schedule changed before advance");
    }
}
