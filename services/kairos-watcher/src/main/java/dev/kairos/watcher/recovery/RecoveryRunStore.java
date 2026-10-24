package dev.kairos.watcher.recovery;

import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.ErrorClass;
import dev.kairos.domain.model.Outcome;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.RunState;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.run.Fence;
import dev.kairos.engine.run.DispatchMessageBuilder;
import dev.kairos.engine.run.InflightRun;
import dev.kairos.persistence.cell.Cell;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Cell-local recovery claims and state transitions under the caller's shard lease. */
public final class RecoveryRunStore {
    private static final String COLUMNS = """
            run_id, team_id, target_type, definition_id, definition_version, target_id, task_key,
            endpoint_id, endpoint_version, scheduled_at, due_at, state, attempt, series_start,
            series_started_at, infra_attempt, fence, priority, trigger, max_lateness_ms,
            owner_node, lease_until
            """;
    private static final String EXPIRED = "SELECT " + COLUMNS + """
              FROM scheduler.run_inflight
             WHERE shard_id = :shard AND state = 3 AND lease_until < :cutoff
             ORDER BY lease_until, run_id
             LIMIT 250 FOR UPDATE SKIP LOCKED
            """;
    private static final String STUCK = "SELECT " + COLUMNS + """
              FROM scheduler.run_inflight
             WHERE shard_id = :shard AND state = 2 AND due_at < :cutoff
             ORDER BY due_at, run_id
             LIMIT 250 FOR UPDATE SKIP LOCKED
            """;
    private static final String QUEUE = """
            UPDATE scheduler.run_inflight
               SET state = 2, due_at = :due, fence = :fence, infra_attempt = :infra,
                   owner_node = NULL, lease_until = NULL, remaining_deps = 0
             WHERE shard_id = :shard AND run_id = :run AND state = :expected
            """;
    private static final String RETRY = """
            UPDATE scheduler.run_inflight
               SET state = 4, due_at = :due, attempt = :attempt, infra_attempt = 0,
                   owner_node = NULL, lease_until = NULL
             WHERE shard_id = :shard AND run_id = :run AND state = 3
            """;
    private static final String HISTORY = """
            INSERT INTO scheduler.run_history (
                scheduled_at, run_id, attempt, infra_attempt, team_id, target_type, definition_id,
                target_id, task_key, endpoint_id, trigger, outcome, error_class, final,
                finished_at, executor_id)
            VALUES (:scheduled, :run, :attempt, :infra, :team, :type, :definition,
                    :target, :task, :endpoint, :trigger, :outcome, :error, :final,
                    :finished, :executor)
            """;
    private static final String DEAD = """
            INSERT INTO scheduler.dead_letters (
                dlq_id, team_id, run_id, shard_id, scheduled_at, target_type, definition_id,
                definition_version, target_id, task_key, endpoint_id, endpoint_version,
                attempt, infra_attempt, priority, reason, fence, created_at)
            VALUES (:dlq, :team, :run, :shard, :scheduled, :type, :definition,
                    :definitionVersion, :target, :task, :endpoint, :endpointVersion,
                    :attempt, :infra, :priority, :reason, :fence, :created)
            """;
    private static final String REMOVE = """
            DELETE FROM scheduler.run_inflight
             WHERE shard_id = :shard AND run_id = :run AND state = 3
            """;

    /** Claims expired executor leases, including the 30-second safety margin. */
    public List<RecoveryRun> expired(Cell cell, int shard, Instant now) {
        return claim(cell, EXPIRED, shard, now.minusSeconds(30));
    }

    /** Claims queued rows whose last dispatch was over ten minutes ago. */
    public List<RecoveryRun> stuck(Cell cell, int shard, Instant now) {
        return claim(cell, STUCK, shard, now.minusSeconds(600));
    }

    private static List<RecoveryRun> claim(Cell cell, String sql, int shard, Instant cutoff) {
        Objects.requireNonNull(cell, "cell");
        return cell.jdbc()
                .sql(sql)
                .param("shard", shard)
                .param("cutoff", Timestamp.from(cutoff))
                .query((rs, row) -> map(rs))
                .list();
    }

    private static RecoveryRun map(ResultSet rs) throws SQLException {
        String task = rs.getString("task_key");
        Timestamp lease = rs.getTimestamp("lease_until");
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
                RunState.fromCode(rs.getInt("state")),
                rs.getInt("attempt"),
                rs.getInt("series_start"),
                rs.getTimestamp("series_started_at").toInstant(),
                rs.getInt("infra_attempt"),
                new Fence(rs.getInt("fence")),
                0,
                new Priority(rs.getInt("priority")),
                Trigger.fromCode(rs.getInt("trigger")),
                rs.getObject("max_lateness_ms", Integer.class));
        return new RecoveryRun(run, rs.getString("owner_node"), lease == null ? null : lease.toInstant());
    }

    /** Stores a fenced redispatch, including its infrastructure-attempt counter. */
    public void queued(Cell cell, RecoveryRun claimed, DispatchMessageBuilder.Redispatch dispatch) {
        InflightRun run = dispatch.run();
        int changed = cell.jdbc()
                .sql(QUEUE)
                .param("shard", run.shard().value())
                .param("run", run.runId().value())
                .param("due", Timestamp.from(run.dueAt()))
                .param("fence", run.fence().value())
                .param("infra", run.infraAttempt())
                .param("expected", claimed.run().state().code())
                .update();
        requireOne(changed, "queue");
    }

    /** Schedules the next attempt after an unknown outcome under retry policy. */
    public void retry(Cell cell, RecoveryRun claimed, int nextAttempt, Instant dueAt) {
        InflightRun run = claimed.run();
        int changed = cell.jdbc()
                .sql(RETRY)
                .param("shard", run.shard().value())
                .param("run", run.runId().value())
                .param("due", Timestamp.from(dueAt))
                .param("attempt", nextAttempt)
                .update();
        requireOne(changed, "retry");
    }

    /** Records the recovered attempt before re-queue, retry or dead-letter. */
    public void history(
            Cell cell, RecoveryRun claimed, Outcome outcome, ErrorClass error, boolean isFinal, Instant now) {
        InflightRun run = claimed.run();
        cell.jdbc()
                .sql(HISTORY)
                .param("scheduled", Timestamp.from(run.scheduledAt()))
                .param("run", run.runId().value())
                .param("attempt", run.attempt())
                .param("infra", run.infraAttempt())
                .param("team", run.teamId().value())
                .param("type", run.targetType().code())
                .param("definition", run.definitionId())
                .param("target", run.targetId())
                .param("task", run.taskKeyColumn())
                .param("endpoint", run.endpointId().value())
                .param("trigger", run.trigger().code())
                .param("outcome", outcome.code())
                .param("error", error.code())
                .param("final", isFinal)
                .param("finished", Timestamp.from(now))
                .param("executor", claimed.ownerNode())
                .update();
    }

    /** Moves a run to the DLQ and returns the durable entry id. */
    public UUID deadLetter(Cell cell, RecoveryRun claimed, UUID dlqId, ErrorClass reason, Instant now) {
        InflightRun run = claimed.run();
        cell.jdbc()
                .sql(DEAD)
                .param("dlq", dlqId)
                .param("team", run.teamId().value())
                .param("run", run.runId().value())
                .param("shard", run.shard().value())
                .param("scheduled", Timestamp.from(run.scheduledAt()))
                .param("type", run.targetType().code())
                .param("definition", run.definitionId())
                .param("definitionVersion", run.definitionVersion())
                .param("target", run.targetId())
                .param("task", run.taskKeyColumn())
                .param("endpoint", run.endpointId().value())
                .param("endpointVersion", run.endpointVersion())
                .param("attempt", run.attempt())
                .param("infra", run.infraAttempt())
                .param("priority", run.priority().value())
                .param("reason", reason.code())
                .param("fence", run.fence().value())
                .param("created", Timestamp.from(now))
                .update();
        int removed = cell.jdbc()
                .sql(REMOVE)
                .param("shard", run.shard().value())
                .param("run", run.runId().value())
                .update();
        requireOne(removed, "dead-letter");
        return dlqId;
    }

    private static void requireOne(int changed, String operation) {
        if (changed != 1) throw new IllegalStateException("Claimed run changed before " + operation);
    }
}
