package dev.kairos.api.workflows.infrastructure;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import dev.kairos.api.workflows.application.WorkflowDefinition;
import dev.kairos.api.workflows.application.WorkflowEndpointCatalog;
import dev.kairos.api.workflows.application.WorkflowEndpointCatalog.Endpoint;
import dev.kairos.api.workflows.application.WorkflowStore;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.NotFoundException;
import dev.kairos.domain.error.PreconditionFailedException;
import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.DstPolicy;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.schedule.ScheduleCalculator;
import dev.kairos.domain.schedule.ScheduleSpec;
import dev.kairos.domain.template.Template;
import dev.kairos.engine.outbox.OutboxMessage;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.engine.run.CallSpec;
import dev.kairos.engine.run.PlannedRun;
import dev.kairos.engine.run.RunCreator;
import dev.kairos.engine.run.RunFactory;
import dev.kairos.engine.run.RunTarget;
import dev.kairos.engine.run.WorkflowRunCreator;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Cancel;
import dev.kairos.proto.v1.CancelReason;
import dev.kairos.proto.v1.RequestSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cell transactions for workflow definitions, occurrences and operator actions. */
public final class JdbcWorkflowStore implements WorkflowStore {
    private static final String FIND = """
            SELECT w.*,w.spec::text AS body,s.next_fire_at FROM scheduler.workflows w
            LEFT JOIN scheduler.schedules s ON s.schedule_id=w.workflow_id
            WHERE w.team_id=:team AND w.workflow_id=:id
            """;
    private static final String RUN = """
            SELECT r.*,v.spec::text AS body FROM scheduler.workflow_runs r
            JOIN scheduler.workflow_versions v ON v.workflow_id=r.workflow_id AND v.version=r.workflow_version
            WHERE r.team_id=:team AND r.workflow_run_id=:id
            """;
    private static final String TASKS = """
            WITH candidates AS (
                SELECT task_key,run_id,CASE WHEN cancel_requested THEN 'CANCELLED' ELSE CASE state
                    WHEN 1 THEN 'WAITING' WHEN 2 THEN 'QUEUED' WHEN 3 THEN 'RUNNING' WHEN 4 THEN 'DELAYED' END END AS status,
                    attempt,infra_attempt,0 AS source
                FROM scheduler.run_inflight WHERE team_id=:team AND target_id=:run AND target_type=2
                UNION ALL
                SELECT h.task_key,h.run_id,CASE h.outcome WHEN 1 THEN 'SUCCEEDED' WHEN 3 THEN 'DEAD_LETTERED'
                    WHEN 4 THEN 'CANCELLED' WHEN 5 THEN 'SKIPPED' WHEN 6 THEN 'UPSTREAM_FAILED' END,
                    h.attempt,h.infra_attempt,1
                FROM scheduler.run_history h WHERE h.team_id=:team AND h.target_id=:run AND h.target_type=2 AND h.final
            ) SELECT DISTINCT ON(task_key) * FROM candidates
            ORDER BY task_key,source,attempt DESC,infra_attempt DESC,run_id DESC
            """;
    private final CellRouter cells;
    private final WorkflowEndpointCatalog endpoints;
    private final KairosIdFactory ids;
    private final RunFactory factory;
    private final RunCreator creator;
    private final OutboxWriter outbox;
    private final JsonMapper json;

    public JdbcWorkflowStore(
            CellRouter cells,
            WorkflowEndpointCatalog endpoints,
            KairosIdFactory ids,
            RunFactory factory,
            RunCreator creator,
            OutboxWriter outbox,
            JsonMapper json) {
        this.cells = cells;
        this.endpoints = endpoints;
        this.ids = ids;
        this.factory = factory;
        this.creator = creator;
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public Optional<Workflow> find(TeamId team, UUID id) {
        return cells.forId(id)
                .jdbc()
                .sql(FIND)
                .param("team", team.value())
                .param("id", id)
                .query(this::workflow)
                .optional();
    }

    @Override
    public List<Workflow> list(TeamId team, @Nullable String status, @Nullable Key after, int limit) {
        List<Workflow> merged = new ArrayList<>();
        for (Cell cell : cells.allCells()) {
            String sql = FIND.replace(
                    "WHERE w.team_id=:team AND w.workflow_id=:id", "WHERE w.team_id=:team AND w.status<>'DELETED'");
            if (status != null) sql += " AND w.status=:status";
            if (after != null) sql += " AND (w.created_at,w.workflow_id)<(:after,:afterId)";
            var query = cell.jdbc()
                    .sql(sql + " ORDER BY w.created_at DESC,w.workflow_id DESC LIMIT :limit")
                    .param("team", team.value())
                    .param("limit", limit);
            if (status != null) query = query.param("status", status);
            if (after != null)
                query = query.param("after", utc(after.createdAt())).param("afterId", after.id());
            merged.addAll(query.query(this::workflow).list());
        }
        return merged.stream()
                .sorted(Comparator.comparing(Workflow::createdAt)
                        .thenComparing(Workflow::id)
                        .reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public Workflow create(TeamId team, UUID id, String actor, WorkflowDefinition definition, Instant now) {
        Cell cell = cells.forId(id);
        cell.transactions().execute(jdbc -> {
            try {
                jdbc.sql("""
                        INSERT INTO scheduler.workflows(workflow_id,team_id,name,version,status,spec,created_by,created_at,updated_at)
                        VALUES (:id,:team,:name,1,'ACTIVE',CAST(:body AS jsonb),:actor,:now,:now)
                        """)
                        .param("id", id)
                        .param("team", team.value())
                        .param("name", definition.name())
                        .param("body", definition.serialized().toString())
                        .param("actor", actor)
                        .param("now", utc(now))
                        .update();
            } catch (DuplicateKeyException duplicate) {
                throw new ConflictException(
                        ErrorCode.WORKFLOW_NAME_TAKEN, "A workflow with this name already exists in the team.");
            }
            saveVersion(jdbc, id, 1, definition.serialized(), now);
            saveSchedule(jdbc, team, id, 1, definition, now, "ACTIVE");
            if (definition.schedule() instanceof ScheduleSpec.Immediate)
                materialize(cell, team, id, 1, definition.serialized(), now, Trigger.IMMEDIATE);
            return null;
        });
        return find(team, id).orElseThrow();
    }

    @Override
    public Workflow update(TeamId team, UUID id, long expected, WorkflowDefinition definition, Instant now) {
        cells.forId(id).transactions().execute(jdbc -> {
            Workflow current = lockWorkflow(jdbc, team, id);
            if (current.version() != expected) throw PreconditionFailedException.versionMismatch();
            operational(current);
            jdbc.sql(
                            "UPDATE scheduler.workflows SET version=version+1,spec=CAST(:body AS jsonb),updated_at=:now WHERE workflow_id=:id AND team_id=:team")
                    .param("body", definition.serialized().toString())
                    .param("now", utc(now))
                    .param("id", id)
                    .param("team", team.value())
                    .update();
            saveVersion(jdbc, id, current.version() + 1, definition.serialized(), now);
            saveSchedule(jdbc, team, id, current.version() + 1, definition, now, current.status());
            return null;
        });
        return find(team, id).orElseThrow();
    }

    @Override
    public Workflow change(TeamId team, UUID id, Change change, Instant now) {
        Cell cell = cells.forId(id);
        cell.transactions().execute(jdbc -> {
            Workflow current = lockWorkflow(jdbc, team, id);
            if (current.status().equals("DELETED")) throw notFound();
            if (change != Change.DELETE) operational(current);
            String next = switch (change) {
                case PAUSE -> "PAUSED";
                case RESUME -> "ACTIVE";
                case CANCEL -> "CANCELLED";
                case DELETE -> "DELETED";
            };
            if (change == Change.PAUSE && !current.status().equals("ACTIVE"))
                invalid("Only an ACTIVE workflow can be paused.");
            if (change == Change.RESUME && !current.status().equals("PAUSED"))
                invalid("Only a PAUSED workflow can be resumed.");
            if (change == Change.DELETE || change == Change.CANCEL) {
                for (UUID run : jdbc.sql(
                                "SELECT workflow_run_id FROM scheduler.workflow_runs WHERE team_id=:team AND workflow_id=:id AND status IN (1,2) ORDER BY workflow_run_id FOR UPDATE")
                        .param("team", team.value())
                        .param("id", id)
                        .query(UUID.class)
                        .list()) cancelRunLocked(jdbc, team, run, now);
                jdbc.sql("DELETE FROM scheduler.schedules WHERE schedule_id=:id")
                        .param("id", id)
                        .update();
            } else
                jdbc.sql("UPDATE scheduler.schedules SET status=:status WHERE schedule_id=:id")
                        .param("status", change == Change.PAUSE ? 2 : 1)
                        .param("id", id)
                        .update();
            jdbc.sql(
                            "UPDATE scheduler.workflows SET status=:status,updated_at=:now WHERE workflow_id=:id AND team_id=:team")
                    .param("status", next)
                    .param("now", utc(now))
                    .param("id", id)
                    .param("team", team.value())
                    .update();
            return null;
        });
        return find(team, id).orElseThrow();
    }

    @Override
    public Run runNow(TeamId team, UUID id, Instant now) {
        Cell cell = cells.forId(id);
        UUID run = cell.transactions().execute(jdbc -> {
            Workflow workflow = lockWorkflow(jdbc, team, id);
            operational(workflow);
            return materialize(cell, team, id, workflow.version(), workflow.spec(), now, Trigger.MANUAL);
        });
        return findRun(team, run).orElseThrow();
    }

    @Override
    public List<Run> runs(TeamId team, UUID id, RunFilter filter, @Nullable RunKey after, int limit) {
        String sql =
                "SELECT r.*,v.spec::text AS body FROM scheduler.workflow_runs r JOIN scheduler.workflow_versions v ON v.workflow_id=r.workflow_id AND v.version=r.workflow_version WHERE r.team_id=:team AND r.workflow_id=:id";
        if (filter.status() != null) sql += " AND r.status=:status";
        if (filter.from() != null) sql += " AND r.scheduled_at>=:from";
        if (filter.to() != null) sql += " AND r.scheduled_at<=:to";
        if (after != null) sql += " AND (r.scheduled_at,r.workflow_run_id)<(:after,:afterId)";
        var query = cells.forId(id)
                .jdbc()
                .sql(sql + " ORDER BY r.scheduled_at DESC,r.workflow_run_id DESC LIMIT :limit")
                .param("team", team.value())
                .param("id", id)
                .param("limit", limit);
        if (filter.status() != null) query = query.param("status", runCode(filter.status()));
        if (filter.from() != null) query = query.param("from", utc(filter.from()));
        if (filter.to() != null) query = query.param("to", utc(filter.to()));
        if (after != null)
            query = query.param("after", utc(after.scheduledAt())).param("afterId", after.id());
        return query.query((rs, row) -> run(rs, List.of())).list();
    }

    @Override
    public Optional<Run> findRun(TeamId team, UUID id) {
        JdbcClient jdbc = cells.forId(id).jdbc();
        Optional<Run> found = jdbc.sql(RUN)
                .param("team", team.value())
                .param("id", id)
                .query((rs, row) -> run(rs, List.of()))
                .optional();
        return found.map(run -> {
            JsonNode body = definition(jdbc, run.workflowId(), run.version());
            Map<String, Map<String, JsonNode>> outputs = outputs(jdbc, team, id, true);
            List<Task> tasks = storedTasks(jdbc, team, id).stream()
                    .map(task -> new Task(
                            task.key(),
                            task.runId(),
                            task.status(),
                            task.status().equals("WAITING") && task.attempt() == 1 ? 0 : task.attempt(),
                            parents(body, task.key()),
                            outputs.getOrDefault(task.key(), Map.of())))
                    .toList();
            return new Run(
                    run.workflowRunId(),
                    run.workflowId(),
                    run.version(),
                    run.status(),
                    run.scheduledAt(),
                    run.startedAt(),
                    run.finishedAt(),
                    run.counts(),
                    tasks);
        });
    }

    @Override
    public Run cancelRun(TeamId team, UUID id, Instant now) {
        cells.forId(id).transactions().execute(jdbc -> {
            lockRun(jdbc, team, id);
            cancelRunLocked(jdbc, team, id, now);
            return null;
        });
        return findRun(team, id).orElseThrow();
    }

    @Override
    public Retry retry(TeamId team, UUID id, String actor, Instant now) {
        Cell cell = cells.forId(id);
        return cell.transactions().execute(jdbc -> {
            Run current = lockRun(jdbc, team, id);
            if (!Set.of("FAILED", "BLOCKED").contains(current.status()))
                invalid("Only a FAILED or BLOCKED workflow run can be retried.");
            Workflow workflow = find(team, current.workflowId()).orElseThrow(JdbcWorkflowStore::notFound);
            operational(workflow);
            JsonNode body = definition(jdbc, current.workflowId(), current.version());
            List<StoredTask> states = storedTasks(jdbc, team, id);
            Set<String> succeeded = states.stream()
                    .filter(t -> t.status().equals("SUCCEEDED"))
                    .map(StoredTask::key)
                    .collect(java.util.stream.Collectors.toSet());
            JsonNode pinned = json.readTree(jdbc.sql(
                            "SELECT task_pins::text FROM scheduler.workflow_runs WHERE workflow_run_id=:id AND team_id=:team")
                    .param("id", id)
                    .param("team", team.value())
                    .query(String.class)
                    .single());
            List<String> retried = new ArrayList<>();
            List<PlannedRun> planned = new ArrayList<>();
            for (StoredTask task : states) {
                if (!Set.of("DEAD_LETTERED", "UPSTREAM_FAILED", "SKIPPED").contains(task.status())) continue;
                JsonNode taskBody = body.path("tasks").path(task.key());
                JsonNode pins = pinned.path(task.key());
                if (!pins.has("endpointId") || !pins.has("version"))
                    throw new IllegalStateException("Workflow task endpoint pin is missing");
                Endpoint endpoint = endpoints.pinned(
                        team,
                        UUID.fromString(pins.path("endpointId").asString()),
                        pins.path("version").asInt());
                active(endpoint);
                int remaining = (int) parents(body, task.key()).stream()
                        .filter(parent -> !succeeded.contains(parent))
                        .count();
                CallSpec call = call(taskBody, endpoint, remaining == 0 ? inputs(jdbc, team, id, taskBody) : null);
                RunTarget target = target(
                        team, current.workflowId(), current.version(), id, task.key(), body, taskBody, endpoint, call);
                planned.add(factory.workflowRetry(target, current.scheduledAt(), task.attempt() + 1, remaining));
                retried.add(task.key());
                jdbc.sql(
                                "DELETE FROM scheduler.task_outputs WHERE team_id=:team AND workflow_run_id=:run AND task_key=:task")
                        .param("team", team.value())
                        .param("run", id)
                        .param("task", task.key())
                        .update();
                jdbc.sql(
                                "UPDATE scheduler.dead_letters SET status=3,resolved_by=:actor,resolved_at=:now,redrive_mode=1 WHERE team_id=:team AND run_id=:run AND status IN (1,2)")
                        .param("actor", actor)
                        .param("now", utc(now))
                        .param("team", team.value())
                        .param("run", task.runId())
                        .update();
            }
            if (retried.isEmpty()) invalid("The workflow run has no failed or skipped tasks to retry.");
            creator.create(planned, null);
            jdbc.sql(
                            "UPDATE scheduler.workflow_runs SET status=1,failed=0,skipped=0,blocked_until=NULL,finished_at=NULL WHERE team_id=:team AND workflow_run_id=:run")
                    .param("team", team.value())
                    .param("run", id)
                    .update();
            return new Retry(id, "RUNNING", List.copyOf(retried));
        });
    }

    private UUID materialize(
            Cell cell, TeamId team, UUID id, int version, JsonNode body, Instant now, Trigger trigger) {
        UUID run = ids.newWorkflowRunId(KairosId.shardOf(id)).value();
        List<WorkflowRunCreator.Task> tasks = new ArrayList<>();
        Map<String, Endpoint> resolved = new LinkedHashMap<>();
        for (var task : body.path("tasks").properties()) {
            JsonNode node = task.getValue();
            String name = node.path("endpoint").asString();
            Endpoint endpoint =
                    resolved.computeIfAbsent(name, n -> endpoints.find(team, n).orElseThrow());
            active(endpoint);
            RunTarget target =
                    target(team, id, version, run, task.getKey(), body, node, endpoint, call(node, endpoint, null));
            tasks.add(new WorkflowRunCreator.Task(
                    target, parents(body, task.getKey()).size()));
        }
        if (!new WorkflowRunCreator(factory, creator)
                .create(cell, team, id, version, run, now, trigger, Duration.ZERO, tasks))
            throw new ConflictException(ErrorCode.INVALID_STATE, "This workflow occurrence already exists.");
        return run;
    }

    private RunTarget target(
            TeamId team,
            UUID definition,
            int version,
            UUID run,
            String key,
            JsonNode body,
            JsonNode task,
            Endpoint endpoint,
            CallSpec call) {
        Duration lateness = task.path("maxLatenessSec").isNumber()
                ? Duration.ofSeconds(task.path("maxLatenessSec").longValue())
                : null;
        return new RunTarget(
                team,
                TargetType.WORKFLOW_TASK,
                definition,
                version,
                run,
                TaskKey.of(key),
                new EndpointId(endpoint.id()),
                endpoint.version(),
                Priority.of(body.path("priority").asInt(5)),
                lateness,
                call);
    }

    private CallSpec call(JsonNode task, Endpoint endpoint, @Nullable byte[] inputs) {
        // Request templates are loaded from the pinned workflow version by the executor.
        // Keep large bodies and credential-bearing templates out of dispatch messages.
        RequestSnapshot.Builder snapshot = RequestSnapshot.newBuilder();
        if (inputs != null) snapshot.setSealedInputs(ByteString.copyFrom(inputs));
        if (endpoint.spec().auth().secretRef() != null)
            snapshot.addSecretRefs(endpoint.spec().auth().secretRef());
        return new CallSpec(endpoint.spec().timeoutMs(), snapshot.build());
    }

    private void saveVersion(JdbcClient jdbc, UUID id, int version, JsonNode body, Instant now) {
        jdbc.sql(
                        "INSERT INTO scheduler.workflow_versions(workflow_id,version,spec,created_at) VALUES (:id,:version,CAST(:body AS jsonb),:now)")
                .param("id", id)
                .param("version", version)
                .param("body", body.toString())
                .param("now", utc(now))
                .update();
    }

    private void saveSchedule(
            JdbcClient jdbc,
            TeamId team,
            UUID id,
            int version,
            WorkflowDefinition definition,
            Instant now,
            String status) {
        if (definition.schedule() instanceof ScheduleSpec.Immediate) {
            jdbc.sql("DELETE FROM scheduler.schedules WHERE schedule_id=:id")
                    .param("id", id)
                    .update();
            return;
        }
        Instant first = ScheduleCalculator.of(definition.schedule(), DstPolicy.DEFAULT, now)
                .first(now)
                .orElseThrow()
                .at();
        String cron = definition.schedule() instanceof ScheduleSpec.Cron value
                ? value.expression().text()
                : null;
        String zone = definition.schedule() instanceof ScheduleSpec.Cron value
                ? value.zone().getId()
                : "UTC";
        Long interval = definition.schedule() instanceof ScheduleSpec.Interval value
                ? value.every().toMillis()
                : null;
        jdbc.sql("""
                INSERT INTO scheduler.schedules(schedule_id,target_type,team_id,endpoint_id,shard_id,status,next_fire_at,
                    kind,cron_expr,timezone,interval_ms,spread_ms,dst_policy,misfire_policy,max_catchup,definition_version,priority)
                VALUES (:id,3,:team,NULL,:shard,:status,:next,:kind,:cron,:zone,:interval,:spread,1,:misfire,:catchup,:version,:priority)
                ON CONFLICT(schedule_id) DO UPDATE SET status=EXCLUDED.status,next_fire_at=EXCLUDED.next_fire_at,
                    kind=EXCLUDED.kind,cron_expr=EXCLUDED.cron_expr,timezone=EXCLUDED.timezone,interval_ms=EXCLUDED.interval_ms,
                    spread_ms=EXCLUDED.spread_ms,misfire_policy=EXCLUDED.misfire_policy,max_catchup=EXCLUDED.max_catchup,
                    definition_version=EXCLUDED.definition_version,priority=EXCLUDED.priority,last_fired_local=NULL
                """)
                .param("id", id)
                .param("team", team.value())
                .param("shard", KairosId.shardOf(id).value())
                .param("status", status.equals("PAUSED") ? 2 : 1)
                .param("next", utc(first))
                .param("kind", definition.schedule().kind().orElseThrow().code())
                .param("cron", cron)
                .param("zone", zone)
                .param("interval", interval)
                .param("spread", definition.spreadSec() * 1000)
                .param("misfire", definition.misfirePolicy().code())
                .param("catchup", definition.maxCatchup())
                .param("version", version)
                .param("priority", definition.priority())
                .update();
    }

    private void cancelRunLocked(JdbcClient jdbc, TeamId team, UUID id, Instant now) {
        Run run = lockRun(jdbc, team, id);
        if (!Set.of("RUNNING", "BLOCKED").contains(run.status()))
            throw new ConflictException(ErrorCode.RUN_FINAL, "The workflow run already finished.");
        var rows = jdbc.sql(
                        "SELECT * FROM scheduler.run_inflight WHERE team_id=:team AND target_id=:run AND target_type=2 ORDER BY run_id FOR UPDATE")
                .param("team", team.value())
                .param("run", id)
                .query((rs, row) -> new CancelTask(
                        rs.getObject("run_id", UUID.class),
                        rs.getInt("state"),
                        rs.getInt("fence"),
                        rs.getBoolean("cancel_requested")))
                .list();
        for (CancelTask task : rows) {
            if (task.state() == 3) {
                if (task.requested()) continue;
                jdbc.sql(
                                "UPDATE scheduler.run_inflight SET cancel_requested=true WHERE run_id=:run AND shard_id=:shard")
                        .param("run", task.id())
                        .param("shard", KairosId.shardOf(id).value())
                        .update();
                Cancel cancel = Cancel.newBuilder()
                        .setRunId(ProtoIds.toBytes(task.id()))
                        .setShard(KairosId.shardOf(id).value())
                        .setFence(task.fence())
                        .setReason(CancelReason.CANCEL_REASON_REQUESTED)
                        .setRequestedAt(Timestamp.newBuilder()
                                .setSeconds(now.getEpochSecond())
                                .setNanos(now.getNano()))
                        .build();
                outbox.write(OutboxMessage.cancel(cancel, now, null));
            } else {
                jdbc.sql("DELETE FROM scheduler.outbox WHERE run_id=:run AND shard_id=:shard AND topic IN (1,2,3)")
                        .param("run", task.id())
                        .param("shard", KairosId.shardOf(id).value())
                        .update();
                jdbc.sql("""
                        INSERT INTO scheduler.run_history(scheduled_at,run_id,attempt,infra_attempt,team_id,target_type,definition_id,
                            target_id,task_key,endpoint_id,trigger,outcome,final,finished_at)
                        SELECT scheduled_at,run_id,attempt,infra_attempt,team_id,target_type,definition_id,target_id,task_key,
                            endpoint_id,trigger,4,true,:now FROM scheduler.run_inflight WHERE run_id=:run AND shard_id=:shard
                        ON CONFLICT DO NOTHING
                        """)
                        .param("now", utc(now))
                        .param("run", task.id())
                        .param("shard", KairosId.shardOf(id).value())
                        .update();
                jdbc.sql("DELETE FROM scheduler.run_inflight WHERE run_id=:run AND shard_id=:shard")
                        .param("run", task.id())
                        .param("shard", KairosId.shardOf(id).value())
                        .update();
            }
        }
        jdbc.sql(
                        "UPDATE scheduler.workflow_runs SET status=5,cancelled=total-succeeded-failed-skipped,finished_at=:now,blocked_until=NULL WHERE workflow_run_id=:id AND team_id=:team")
                .param("now", utc(now))
                .param("id", id)
                .param("team", team.value())
                .update();
    }

    private Workflow lockWorkflow(JdbcClient jdbc, TeamId team, UUID id) {
        jdbc.sql("SELECT workflow_id FROM scheduler.workflows WHERE team_id=:team AND workflow_id=:id FOR UPDATE")
                .param("team", team.value())
                .param("id", id)
                .query(UUID.class)
                .optional()
                .orElseThrow(JdbcWorkflowStore::notFound);
        return find(team, id).orElseThrow();
    }

    private Run lockRun(JdbcClient jdbc, TeamId team, UUID id) {
        return jdbc.sql(RUN + " FOR UPDATE OF r")
                .param("team", team.value())
                .param("id", id)
                .query((rs, row) -> run(rs, List.of()))
                .optional()
                .orElseThrow(() -> new NotFoundException("The workflow run does not exist."));
    }

    private JsonNode definition(JdbcClient jdbc, UUID id, int version) {
        return json.readTree(jdbc.sql(
                        "SELECT spec::text FROM scheduler.workflow_versions WHERE workflow_id=:id AND version=:version")
                .param("id", id)
                .param("version", version)
                .query(String.class)
                .single());
    }

    private List<StoredTask> storedTasks(JdbcClient jdbc, TeamId team, UUID run) {
        return jdbc.sql(TASKS)
                .param("team", team.value())
                .param("run", run)
                .query((rs, row) -> new StoredTask(
                        rs.getString("task_key"),
                        rs.getObject("run_id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("attempt")))
                .list();
    }

    private Map<String, Map<String, JsonNode>> outputs(JdbcClient jdbc, TeamId team, UUID id, boolean redacted) {
        Map<String, Map<String, JsonNode>> result = new LinkedHashMap<>();
        jdbc.sql(
                        "SELECT task_key,outputs::text,sealed FROM scheduler.task_outputs WHERE team_id=:team AND workflow_run_id=:run")
                .param("team", team.value())
                .param("run", id)
                .query((rs, row) -> {
                    Map<String, JsonNode> values = new LinkedHashMap<>();
                    String raw = rs.getString(2);
                    if (raw != null)
                        json.readTree(raw).properties().forEach(entry -> values.put(entry.getKey(), entry.getValue()));
                    byte[] sealed = rs.getBytes(3);
                    if (redacted && sealed != null && sealed.length > 0)
                        json.readTree(sealed)
                                .path("values")
                                .properties()
                                .forEach(entry -> values.put(entry.getKey(), json.valueToTree("[REDACTED]")));
                    result.put(rs.getString(1), Map.copyOf(values));
                    return 1;
                })
                .list();
        return result;
    }

    private byte[] inputs(JdbcClient jdbc, TeamId team, UUID run, JsonNode task) {
        Map<String, Set<String>> needed = new LinkedHashMap<>();
        collect(task.path("request"), needed);
        Map<String, Map<String, JsonNode>> publicTasks = new LinkedHashMap<>(), sealedTasks = new LinkedHashMap<>();
        jdbc.sql(
                        "SELECT task_key,outputs::text,sealed FROM scheduler.task_outputs WHERE team_id=:team AND workflow_run_id=:run")
                .param("team", team.value())
                .param("run", run)
                .query((rs, row) -> {
                    String key = rs.getString(1);
                    Set<String> names = needed.get(key);
                    if (names == null) return 0;
                    String raw = rs.getString(2);
                    Map<String, JsonNode> plain = new LinkedHashMap<>(), sealed = new LinkedHashMap<>();
                    if (raw != null)
                        json.readTree(raw).properties().forEach(entry -> {
                            if (names.contains(entry.getKey())) plain.put(entry.getKey(), entry.getValue());
                        });
                    byte[] ciphertext = rs.getBytes(3);
                    if (ciphertext != null && ciphertext.length > 0)
                        json.readTree(ciphertext).path("values").properties().forEach(entry -> {
                            if (names.contains(entry.getKey())) sealed.put(entry.getKey(), entry.getValue());
                        });
                    if (!plain.isEmpty()) publicTasks.put(key, plain);
                    if (!sealed.isEmpty()) sealedTasks.put(key, sealed);
                    return 1;
                })
                .list();
        return json.writeValueAsBytes(Map.of("v", 1, "tasks", sealedTasks, "publicTasks", publicTasks));
    }

    private static void collect(JsonNode node, Map<String, Set<String>> references) {
        if (node.isString())
            for (var reference : Template.compile(node.asString()).references()) {
                if (reference instanceof Template.OutputReference output)
                    references
                            .computeIfAbsent(output.task().value(), ignored -> new java.util.HashSet<>())
                            .add(output.output());
            }
        else if (node.isObject()) node.properties().forEach(entry -> collect(entry.getValue(), references));
        else if (node.isArray()) node.forEach(child -> collect(child, references));
    }

    private static List<String> parents(JsonNode body, String key) {
        JsonNode dependencies = body.path("tasks").path(key).path("dependsOn");
        List<String> result = new ArrayList<>();
        if (dependencies.isArray()) dependencies.forEach(parent -> result.add(parent.asString()));
        return List.copyOf(result);
    }

    private Workflow workflow(ResultSet rs, int row) throws SQLException {
        return new Workflow(
                rs.getObject("workflow_id", UUID.class),
                rs.getString("name"),
                rs.getString("status"),
                rs.getInt("version"),
                json.readTree(rs.getString("body")),
                instant(rs, "next_fire_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Run run(ResultSet rs, List<Task> tasks) throws SQLException {
        return new Run(
                rs.getObject("workflow_run_id", UUID.class),
                rs.getObject("workflow_id", UUID.class),
                rs.getInt("workflow_version"),
                status(rs.getInt("status")),
                rs.getTimestamp("scheduled_at").toInstant(),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                new Counts(
                        rs.getInt("total"),
                        rs.getInt("succeeded"),
                        rs.getInt("failed"),
                        rs.getInt("cancelled"),
                        rs.getInt("skipped")),
                tasks);
    }

    private static @Nullable Instant instant(ResultSet rs, String field) throws SQLException {
        var value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant time) {
        return OffsetDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private static String status(int status) {
        return List.of("RUNNING", "BLOCKED", "SUCCEEDED", "FAILED", "CANCELLED").get(status - 1);
    }

    private static int runCode(String status) {
        return List.of("RUNNING", "BLOCKED", "SUCCEEDED", "FAILED", "CANCELLED").indexOf(status) + 1;
    }

    private static void active(Endpoint endpoint) {
        if (!endpoint.status().equals("ACTIVE"))
            throw new ConflictException(ErrorCode.ENDPOINT_SUSPENDED, "The task endpoint is not active.");
    }

    private static void operational(Workflow workflow) {
        if (workflow.status().equals("DELETED")) throw notFound();
        if (workflow.status().equals("CANCELLED")) invalid("A cancelled workflow cannot be changed or run.");
    }

    private static void invalid(String detail) {
        throw new ConflictException(ErrorCode.INVALID_STATE, detail);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("The workflow does not exist.");
    }

    private record StoredTask(String key, UUID runId, String status, int attempt) {}

    private record CancelTask(UUID id, int state, int fence, boolean requested) {}
}
