package dev.kairos.watcher.singleton;

import dev.kairos.domain.model.Outcome;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Expires blocked workflow reviews and records their dependent tasks as upstream-failed. */
public final class ReviewTimeoutSweeper {
    private static final String CLAIM = """
            SELECT workflow_run_id, workflow_id, workflow_version
              FROM scheduler.workflow_runs
             WHERE status = 2 AND blocked_until <= :now
             ORDER BY blocked_until, workflow_run_id
             LIMIT 100 FOR UPDATE SKIP LOCKED
            """;
    private static final String DEAD_TASKS = """
            SELECT DISTINCT task_key FROM scheduler.dead_letters
             WHERE target_type = 2 AND target_id = :workflowRun AND status IN (1, 2)
            """;
    private static final String VERSION = """
            SELECT spec::text FROM scheduler.workflow_versions
             WHERE workflow_id = :workflow AND version = :version
            """;
    private static final String WAITING = """
            SELECT shard_id, run_id, team_id, target_type, definition_id, definition_version, target_id,
                   task_key, endpoint_id, endpoint_version, scheduled_at, attempt, infra_attempt, trigger
              FROM scheduler.run_inflight
             WHERE target_type = 2 AND target_id = :workflowRun AND state = 1 AND task_key IN (:tasks)
             ORDER BY task_key, run_id
             FOR UPDATE SKIP LOCKED
            """;
    private static final String HISTORY = """
            INSERT INTO scheduler.run_history (
                scheduled_at, run_id, attempt, infra_attempt, team_id, target_type, definition_id,
                target_id, task_key, endpoint_id, trigger, outcome, error_class, final, finished_at)
            VALUES (:scheduled, :run, :attempt, :infra, :team, :targetType, :definition,
                    :target, :task, :endpoint, :trigger, :outcome, NULL, true, :finished)
            """;
    private static final String REMOVE = """
            DELETE FROM scheduler.run_inflight WHERE shard_id = :shard AND run_id = :run AND state = 1
            """;
    private static final String UNBLOCK = """
            UPDATE scheduler.workflow_runs
               SET status = 1, blocked_until = NULL, review_expired = true, failed = failed + :failed
             WHERE workflow_run_id = :workflowRun AND status = 2
            """;

    private final CellRouter cells;
    private final JsonMapper json;
    private final Clock clock;

    /** Creates the sweeper. */
    public ReviewTimeoutSweeper(CellRouter cells, JsonMapper json, Clock clock) {
        this.cells = Objects.requireNonNull(cells, "cells");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Processes expired blocked workflows in each cell, in bounded batches. */
    public int poll() {
        int changed = 0;
        for (Cell cell : cells.allCells()) {
            changed += cell.transactions().execute(jdbc -> expire(cell.jdbc(), clock.instant()));
        }
        return changed;
    }

    private int expire(JdbcClient jdbc, Instant now) {
        List<BlockedWorkflow> claimed = jdbc.sql(CLAIM)
                .param("now", Timestamp.from(now))
                .query((rs, row) -> new BlockedWorkflow(
                        rs.getObject("workflow_run_id", UUID.class),
                        rs.getObject("workflow_id", UUID.class),
                        rs.getInt("workflow_version")))
                .list();
        int changed = 0;
        for (BlockedWorkflow workflow : claimed) {
            List<String> roots = jdbc.sql(DEAD_TASKS)
                    .param("workflowRun", workflow.runId())
                    .query(String.class)
                    .list();
            JsonNode spec = json.readTree(jdbc.sql(VERSION)
                    .param("workflow", workflow.workflowId())
                    .param("version", workflow.version())
                    .query(String.class)
                    .single());
            Set<String> dependents = descendants(spec.path("tasks"), roots);
            List<WaitingTask> waiting = dependents.isEmpty()
                    ? List.of()
                    : jdbc.sql(WAITING)
                            .param("workflowRun", workflow.runId())
                            .param("tasks", dependents)
                            .query((rs, row) -> map(rs))
                            .list();
            for (WaitingTask task : waiting) {
                jdbc.sql(HISTORY)
                        .param("scheduled", Timestamp.from(task.scheduledAt()))
                        .param("run", task.runId())
                        .param("attempt", task.attempt())
                        .param("infra", task.infraAttempt())
                        .param("team", task.teamId())
                        .param("targetType", task.targetType())
                        .param("definition", task.definitionId())
                        .param("target", task.targetId())
                        .param("task", task.taskKey())
                        .param("endpoint", task.endpointId())
                        .param("trigger", task.trigger())
                        .param("outcome", Outcome.UPSTREAM_FAILED.code())
                        .param("finished", Timestamp.from(now))
                        .update();
                int removed = jdbc.sql(REMOVE)
                        .param("shard", task.shard())
                        .param("run", task.runId())
                        .update();
                if (removed != 1) throw new IllegalStateException("Waiting task changed before review timeout");
            }
            int resumed = jdbc.sql(UNBLOCK)
                    .param("failed", waiting.size())
                    .param("workflowRun", workflow.runId())
                    .update();
            if (resumed == 1) changed += waiting.size();
        }
        return changed;
    }

    private Set<String> descendants(JsonNode tasks, List<String> roots) {
        Map<String, List<String>> children = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : tasks.properties()) {
            JsonNode dependencies = entry.getValue().path("dependsOn");
            if (!dependencies.isArray()) continue;
            for (JsonNode dependency : dependencies) {
                children.computeIfAbsent(dependency.asString(), ignored -> new ArrayList<>())
                        .add(entry.getKey());
            }
        }
        Set<String> descendants = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            for (String child : children.getOrDefault(queue.removeFirst(), List.of())) {
                if (descendants.add(child)) queue.addLast(child);
            }
        }
        return descendants;
    }

    private static WaitingTask map(ResultSet rs) throws SQLException {
        return new WaitingTask(
                rs.getInt("shard_id"),
                rs.getObject("run_id", UUID.class),
                rs.getObject("team_id", UUID.class),
                rs.getInt("target_type"),
                rs.getObject("definition_id", UUID.class),
                rs.getObject("target_id", UUID.class),
                rs.getString("task_key"),
                rs.getObject("endpoint_id", UUID.class),
                rs.getTimestamp("scheduled_at").toInstant(),
                rs.getInt("attempt"),
                rs.getInt("infra_attempt"),
                rs.getInt("trigger"));
    }

    private record BlockedWorkflow(UUID runId, UUID workflowId, int version) {}

    private record WaitingTask(
            int shard,
            UUID runId,
            UUID teamId,
            int targetType,
            UUID definitionId,
            UUID targetId,
            String taskKey,
            UUID endpointId,
            Instant scheduledAt,
            int attempt,
            int infraAttempt,
            int trigger) {}
}
