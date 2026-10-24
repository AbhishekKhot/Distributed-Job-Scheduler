package dev.kairos.watcher.delayed;

import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.RunState;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.run.Fence;
import dev.kairos.engine.run.DispatchMessageBuilder;
import dev.kairos.engine.run.InflightRun;
import dev.kairos.persistence.cell.Cell;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Claims due delayed rows and persists their next durable state under the caller's shard lease. */
public final class DelayedRunStore {
    private static final String CLAIM = """
            SELECT run_id, team_id, target_type, definition_id, definition_version, target_id, task_key,
                   endpoint_id, endpoint_version, scheduled_at, due_at, attempt, series_start,
                   series_started_at, infra_attempt, deferrals, fence, priority, trigger, max_lateness_ms
              FROM scheduler.run_inflight
             WHERE shard_id = :shard AND state = 4 AND due_at <= :now
             ORDER BY due_at, run_id
             LIMIT 250 FOR UPDATE SKIP LOCKED
            """;
    private static final String QUEUE = """
            UPDATE scheduler.run_inflight
               SET state = 2, due_at = :due, fence = :fence, remaining_deps = 0,
                   owner_node = NULL, lease_until = NULL
             WHERE shard_id = :shard AND run_id = :run AND state = 4
            """;
    private static final String DEFER = """
            UPDATE scheduler.run_inflight
               SET due_at = :due, deferrals = deferrals + 1
             WHERE shard_id = :shard AND run_id = :run AND state = 4
            """;

    /** Claims at most 250 due runs for one shard. */
    public List<DelayedRun> claim(Cell cell, int shard, Instant now) {
        Objects.requireNonNull(cell, "cell");
        return cell.jdbc()
                .sql(CLAIM)
                .param("shard", shard)
                .param("now", Timestamp.from(now))
                .query((rs, row) -> {
                    String task = rs.getString("task_key");
                    InflightRun run = new InflightRun(
                            new RunId(rs.getObject("run_id", UUID.class)),
                            new TeamId(rs.getObject("team_id", UUID.class)),
                            TargetType.fromCode(rs.getInt("target_type")),
                            rs.getObject("definition_id", UUID.class),
                            rs.getInt("definition_version"),
                            rs.getObject("target_id", UUID.class),
                            task.isEmpty() ? null : new TaskKey(task),
                            new EndpointId(rs.getObject("endpoint_id", UUID.class)),
                            rs.getInt("endpoint_version"),
                            rs.getTimestamp("scheduled_at").toInstant(),
                            rs.getTimestamp("due_at").toInstant(),
                            RunState.DELAYED,
                            rs.getInt("attempt"),
                            rs.getInt("series_start"),
                            rs.getTimestamp("series_started_at").toInstant(),
                            rs.getInt("infra_attempt"),
                            new Fence(rs.getInt("fence")),
                            0,
                            new Priority(rs.getInt("priority")),
                            Trigger.fromCode(rs.getInt("trigger")),
                            rs.getObject("max_lateness_ms", Integer.class));
                    return new DelayedRun(run, rs.getInt("deferrals"));
                })
                .list();
    }

    /** Stores the queued state returned by the shared dispatch builder. */
    public void queued(Cell cell, DispatchMessageBuilder.Redispatch dispatch) {
        InflightRun run = dispatch.run();
        int changed = cell.jdbc()
                .sql(QUEUE)
                .param("shard", run.shard().value())
                .param("run", run.runId().value())
                .param("due", Timestamp.from(run.dueAt()))
                .param("fence", run.fence().value())
                .update();
        if (changed != 1) throw new IllegalStateException("Claimed delayed run changed before queueing");
    }

    /** Pushes a run forward while its endpoint remains suspended. */
    public void defer(Cell cell, DelayedRun delayed, Instant now, Duration delay) {
        deferUntil(cell, delayed, now.plus(delay));
    }

    /** Pushes a run to an exact durable pacing window and increments its deferral count. */
    public void deferUntil(Cell cell, DelayedRun delayed, Instant dueAt) {
        InflightRun run = delayed.run();
        int changed = cell.jdbc()
                .sql(DEFER)
                .param("shard", run.shard().value())
                .param("run", run.runId().value())
                .param("due", Timestamp.from(dueAt))
                .update();
        if (changed != 1) throw new IllegalStateException("Claimed delayed run changed before deferral");
    }
}
