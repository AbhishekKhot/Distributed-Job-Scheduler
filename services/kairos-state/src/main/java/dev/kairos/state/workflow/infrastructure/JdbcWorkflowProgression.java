package dev.kairos.state.workflow.infrastructure;

import com.google.protobuf.ByteString;
import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.Outcome;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.RunState;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.run.Fence;
import dev.kairos.engine.outbox.CellTransactionGuard;
import dev.kairos.engine.outbox.OutboxMessage;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.engine.run.CallSpec;
import dev.kairos.engine.run.DispatchMessageBuilder;
import dev.kairos.engine.run.InflightRun;
import dev.kairos.engine.run.WorkflowInputs;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.proto.ProtoTimestamps;
import dev.kairos.proto.v1.RequestSnapshot;
import dev.kairos.proto.v1.Result;
import dev.kairos.state.settle.application.WorkflowResultHandler;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Advances the current task executions under the workflow-run lock, in the settlement transaction. */
public final class JdbcWorkflowProgression implements WorkflowResultHandler {
    private static final String TASKS = """
            WITH candidates AS (
                SELECT task_key,run_id,attempt,infra_attempt,0 AS source,0 AS outcome,state FROM scheduler.run_inflight
                WHERE shard_id=:shard AND team_id=:team AND target_id=:run AND target_type=2
                UNION ALL
                SELECT task_key,run_id,attempt,infra_attempt,1 AS source,outcome,0 AS state FROM scheduler.run_history
                WHERE team_id=:team AND target_id=:run AND target_type=2 AND final
            ) SELECT DISTINCT ON(task_key) * FROM candidates
            ORDER BY task_key,source,attempt DESC,infra_attempt DESC,run_id DESC
            """;
    private final CellRouter cells;
    private final JdbcClient control;
    private final OutboxWriter outbox;
    private final WorkflowOutputs outputs;
    private final JsonMapper json;
    private final Clock clock;
    private final CellTransactionGuard guard;

    public JdbcWorkflowProgression(
            CellRouter cells,
            JdbcClient control,
            OutboxWriter outbox,
            WorkflowOutputs outputs,
            JsonMapper json,
            Clock clock) {
        this.cells = cells;
        this.control = control;
        this.outbox = outbox;
        this.outputs = outputs;
        this.json = json;
        this.clock = clock;
        this.guard = new CellTransactionGuard(cells);
    }

    @Override
    public void settled(InflightRun run, Result result, Outcome outcome, Instant at) {
        Cell cell = guard.requireTransaction(run.shard());
        Workflow workflow = lock(cell, run.targetId());
        if (!workflow.team().equals(run.teamId())
                || !workflow.definition().equals(run.definitionId())
                || workflow.version() != run.definitionVersion())
            throw new IllegalStateException("Task does not belong to the pinned workflow occurrence");
        if (workflow.status() == 5) return; // Operator cancellation already counted every task, including active calls.
        Instant started = ProtoTimestamps.toInstant(result.getStartedAt());
        cell.jdbc()
                .sql(
                        "UPDATE scheduler.workflow_runs SET started_at=LEAST(started_at,:started) WHERE workflow_run_id=:id")
                .param("started", started.atOffset(ZoneOffset.UTC))
                .param("id", run.targetId())
                .update();
        if (outcome == Outcome.SUCCEEDED) outputs.store(cell, run, result, at);
        advance(cell, workflow, at);
    }

    /** Reconciles API retries, review expiry and watcher recovery, which do not require another Kafka result. */
    public void reconcile(Cell cell, UUID id) {
        var shard = dev.kairos.domain.id.KairosId.shardOf(id);
        guard.requireTransaction(shard);
        Workflow workflow = lock(cell, id);
        if (workflow.status() != 3 && workflow.status() != 5) advance(cell, workflow, clock.instant());
    }

    private Workflow lock(Cell cell, UUID id) {
        return cell.jdbc()
                .sql("""
                SELECT r.*,v.spec::text AS spec FROM scheduler.workflow_runs r JOIN scheduler.workflow_versions v
                ON v.workflow_id=r.workflow_id AND v.version=r.workflow_version
                WHERE r.workflow_run_id=:id FOR UPDATE OF r
                """)
                .param("id", id)
                .query((rs, n) -> new Workflow(
                        id,
                        rs.getObject("workflow_id", UUID.class),
                        rs.getInt("workflow_version"),
                        new TeamId(rs.getObject("team_id", UUID.class)),
                        rs.getInt("shard_id"),
                        rs.getInt("status"),
                        rs.getInt("total"),
                        json.readTree(rs.getString("spec")),
                        json.readTree(rs.getString("task_pins")),
                        rs.getTimestamp("blocked_until") == null
                                ? null
                                : rs.getTimestamp("blocked_until").toInstant(),
                        rs.getBoolean("review_expired")))
                .single();
    }

    private void advance(Cell cell, Workflow workflow, Instant now) {
        Map<String, TaskState> states = states(cell, workflow);
        JsonNode tasks = workflow.spec().path("tasks");
        if (states.size() != workflow.total()
                || tasks.size() != workflow.total()
                || !states.keySet()
                        .equals(tasks.properties().stream()
                                .map(Map.Entry::getKey)
                                .collect(java.util.stream.Collectors.toSet())))
            throw new IllegalStateException("Workflow current task state is incomplete");
        boolean expired = workflow.expired()
                || (workflow.status() == 2
                        && workflow.deadline() != null
                        && !workflow.deadline().isAfter(now));
        boolean failFast = workflow.spec()
                .path("onTaskFailure")
                .asString("BLOCK_FOR_REVIEW")
                .equals("FAIL_FAST");
        Set<String> dead = new HashSet<>(), propagate = new HashSet<>();
        for (TaskState task : states.values()) {
            if (task.outcome() == 3) {
                dead.add(task.key());
                if (failFast || expired) propagate.add(task.key());
            } else if (task.outcome() == 4 || task.outcome() == 5 || task.outcome() == 6) propagate.add(task.key());
        }
        // A discarded DLQ entry is a manual final decision; its descendants can no longer wait for a redrive.
        if (!dead.isEmpty())
            cell.jdbc()
                    .sql(
                            "SELECT task_key FROM scheduler.dead_letters WHERE team_id=:team AND target_id=:run AND target_type=2 AND status=4 AND task_key IN (:tasks)")
                    .param("team", workflow.team().value())
                    .param("run", workflow.id())
                    .param("tasks", dead)
                    .query(String.class)
                    .list()
                    .forEach(propagate::add);
        Set<String> descendants = descendants(tasks, propagate);
        if (!descendants.isEmpty()) {
            cell.jdbc()
                    .sql("""
                    INSERT INTO scheduler.run_history(scheduled_at,run_id,attempt,infra_attempt,team_id,target_type,definition_id,
                      target_id,task_key,endpoint_id,trigger,outcome,final,finished_at)
                    SELECT scheduled_at,run_id,attempt,infra_attempt,team_id,target_type,definition_id,target_id,task_key,
                      endpoint_id,trigger,6,true,:now FROM scheduler.run_inflight
                    WHERE shard_id=:shard AND target_id=:run AND team_id=:team AND target_type=2 AND state=1 AND task_key IN (:tasks)
                    """)
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .param("shard", workflow.shard())
                    .param("run", workflow.id())
                    .param("team", workflow.team().value())
                    .param("tasks", descendants)
                    .update();
            cell.jdbc()
                    .sql(
                            "DELETE FROM scheduler.run_inflight WHERE shard_id=:shard AND target_id=:run AND team_id=:team AND target_type=2 AND state=1 AND task_key IN (:tasks)")
                    .param("shard", workflow.shard())
                    .param("run", workflow.id())
                    .param("team", workflow.team().value())
                    .param("tasks", descendants)
                    .update();
            states = states(cell, workflow);
        }
        List<OutboxMessage> dispatches = new ArrayList<>();
        Map<String, TaskState> current = states;
        cell.jdbc()
                .sql(
                        "SELECT * FROM scheduler.run_inflight WHERE shard_id=:shard AND target_id=:run AND team_id=:team AND target_type=2 AND state=1 ORDER BY run_id FOR UPDATE")
                .param("shard", workflow.shard())
                .param("run", workflow.id())
                .param("team", workflow.team().value())
                .query((rs, n) -> row(rs))
                .list()
                .forEach(waiting -> {
                    JsonNode task = tasks.path(waiting.taskKeyColumn());
                    int remaining = 0;
                    for (JsonNode parent : task.path("dependsOn"))
                        if (current.get(parent.asString()).outcome() != 1) remaining++;
                    if (remaining > 0) {
                        cell.jdbc()
                                .sql(
                                        "UPDATE scheduler.run_inflight SET remaining_deps=:remaining WHERE shard_id=:shard AND run_id=:id")
                                .param("remaining", remaining)
                                .param("shard", workflow.shard())
                                .param("id", waiting.runId().value())
                                .update();
                        return;
                    }
                    JsonNode pin = workflow.pins().path(waiting.taskKeyColumn());
                    if (!pin.path("endpointId")
                                    .asString()
                                    .equals(waiting.endpointId().value().toString())
                            || pin.path("version").asInt() != waiting.endpointVersion())
                        throw new IllegalStateException("Workflow endpoint pin is inconsistent");
                    CallSpec call = call(cell, waiting, task);
                    var queued = DispatchMessageBuilder.redispatch(waiting, call, now, null);
                    int changed = cell.jdbc()
                            .sql(
                                    "UPDATE scheduler.run_inflight SET state=2,remaining_deps=0,fence=:fence,due_at=:now WHERE shard_id=:shard AND run_id=:id AND state=1 AND fence=:before")
                            .param("fence", queued.run().fence().value())
                            .param("now", now.atOffset(ZoneOffset.UTC))
                            .param("shard", workflow.shard())
                            .param("id", waiting.runId().value())
                            .param("before", waiting.fence().value())
                            .update();
                    if (changed != 1) throw new IllegalStateException("Waiting task changed during progression");
                    dispatches.add(OutboxMessage.dispatch(queued.message(), now));
                });
        outbox.write(dispatches);
        int succeeded = 0, failed = 0, cancelled = 0, skipped = 0;
        for (TaskState task : states.values())
            switch (task.outcome()) {
                case 1 -> succeeded++;
                case 3, 6 -> failed++;
                case 4 -> cancelled++;
                case 5 -> skipped++;
                default -> {}
            }
        Set<String> review = new HashSet<>(dead);
        review.removeAll(propagate);
        boolean blocked = !failFast && !expired && !review.isEmpty();
        boolean finished = !blocked && succeeded + failed + cancelled + skipped == workflow.total();
        int status = blocked ? 2 : finished ? (failed > 0 ? 4 : cancelled > 0 ? 5 : 3) : 1;
        Instant deadline = blocked
                ? (workflow.deadline() == null
                        ? now.plusSeconds(
                                workflow.spec().path("reviewTimeoutSec").asInt(86400))
                        : workflow.deadline())
                : null;
        cell.jdbc()
                .sql("""
                UPDATE scheduler.workflow_runs SET status=:status,succeeded=:succeeded,failed=:failed,cancelled=:cancelled,
                  skipped=:skipped,blocked_until=:deadline,review_expired=:expired,finished_at=:finished
                WHERE workflow_run_id=:id
                """)
                .param("status", status)
                .param("succeeded", succeeded)
                .param("failed", failed)
                .param("cancelled", cancelled)
                .param("skipped", skipped)
                .param("deadline", deadline == null ? null : deadline.atOffset(ZoneOffset.UTC))
                .param("expired", expired)
                .param("finished", finished ? now.atOffset(ZoneOffset.UTC) : null)
                .param("id", workflow.id())
                .update();
    }

    private CallSpec call(Cell cell, InflightRun run, JsonNode task) {
        JsonNode endpoint = json.readTree(control.sql(
                        "SELECT v.spec::text FROM scheduler.api_endpoint_versions v JOIN scheduler.api_endpoints e ON e.endpoint_id=v.endpoint_id WHERE e.team_id=:team AND v.endpoint_id=:id AND v.version=:version")
                .param("team", run.teamId().value())
                .param("id", run.endpointId().value())
                .param("version", run.endpointVersion())
                .query(String.class)
                .single());
        var snapshot = RequestSnapshot.newBuilder()
                .setSealedInputs(ByteString.copyFrom(
                        WorkflowInputs.load(cell.jdbc(), run.teamId(), run.targetId(), task.path("request"), json)));
        if (endpoint.path("auth").path("secretRef").isString())
            snapshot.addSecretRefs(endpoint.path("auth").path("secretRef").asString());
        return new CallSpec(endpoint.path("timeoutMs").asInt(30000), snapshot.build());
    }

    private Map<String, TaskState> states(Cell cell, Workflow workflow) {
        Map<String, TaskState> result = new LinkedHashMap<>();
        cell.jdbc()
                .sql(TASKS)
                .param("shard", workflow.shard())
                .param("team", workflow.team().value())
                .param("run", workflow.id())
                .query((rs, n) -> {
                    var state = new TaskState(rs.getString("task_key"), rs.getInt("outcome"));
                    result.put(state.key(), state);
                    return 1;
                })
                .list();
        return result;
    }

    private static Set<String> descendants(JsonNode tasks, Set<String> roots) {
        Map<String, List<String>> children = new HashMap<>();
        tasks.properties()
                .forEach(e -> e.getValue()
                        .path("dependsOn")
                        .forEach(p -> children.computeIfAbsent(p.asString(), ignored -> new ArrayList<>())
                                .add(e.getKey())));
        Set<String> result = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty())
            for (String child : children.getOrDefault(pending.removeFirst(), List.of()))
                if (result.add(child)) pending.addLast(child);
        return result;
    }

    private static InflightRun row(ResultSet rs) throws SQLException {
        return new InflightRun(
                new RunId(rs.getObject("run_id", UUID.class)),
                new TeamId(rs.getObject("team_id", UUID.class)),
                TargetType.WORKFLOW_TASK,
                rs.getObject("definition_id", UUID.class),
                rs.getInt("definition_version"),
                rs.getObject("target_id", UUID.class),
                TaskKey.of(rs.getString("task_key")),
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
                rs.getInt("remaining_deps"),
                Priority.of(rs.getInt("priority")),
                Trigger.fromCode(rs.getInt("trigger")),
                rs.getObject("max_lateness_ms", Integer.class));
    }

    private record TaskState(String key, int outcome) {}

    private record Workflow(
            UUID id,
            UUID definition,
            int version,
            TeamId team,
            int shard,
            int status,
            int total,
            JsonNode spec,
            JsonNode pins,
            @Nullable Instant deadline,
            boolean expired) {}
}
