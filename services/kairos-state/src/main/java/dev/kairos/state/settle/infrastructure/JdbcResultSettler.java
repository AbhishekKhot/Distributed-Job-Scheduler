package dev.kairos.state.settle.infrastructure;

import dev.kairos.domain.health.AutoPause;
import dev.kairos.domain.health.JobHealth;
import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.KairosIdFactory;
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
import dev.kairos.engine.outbox.OutboxMessage;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.engine.run.InflightRun;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.ProtoTimestamps;
import dev.kairos.proto.v1.Completed;
import dev.kairos.proto.v1.Dead;
import dev.kairos.proto.v1.Result;
import dev.kairos.state.settle.application.ResultDecider;
import dev.kairos.state.settle.application.ResultMetadata;
import dev.kairos.state.settle.application.RunPolicy;
import dev.kairos.state.settle.application.SettlementBatch;
import dev.kairos.state.settle.application.WorkflowResultHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Locks parents before tasks, settles each cell atomically, and exposes only committed effects to observers. */
public final class JdbcResultSettler implements SettlementBatch {
    private static final String HISTORY = """
            INSERT INTO scheduler.run_history(scheduled_at,run_id,attempt,infra_attempt,team_id,target_type,definition_id,
                target_id,task_key,endpoint_id,trigger,outcome,error_class,final,started_at,finished_at,http_status,latency_ms,
                executor_id,error,response_snippet)
            VALUES (:scheduled,:run,:attempt,:infra,:team,:type,:definition,:target,:task,:endpoint,:trigger,:outcome,:errorClass,
                :final,:started,:finished,:http,:latency,:executor,:error,:snippet)
            """;
    private final CellRouter cells;
    private final SettlementPolicyLoader policies;
    private final ResultDecider decider;
    private final WorkflowResultHandler workflows;
    private final List<Observer> observers;
    private final OutboxWriter outbox;
    private final KairosIdFactory ids;
    private final Clock clock;
    private final JsonMapper json;
    private final MeterRegistry meters;

    public JdbcResultSettler(
            CellRouter cells,
            SettlementPolicyLoader policies,
            ResultDecider decider,
            WorkflowResultHandler workflows,
            List<Observer> observers,
            OutboxWriter outbox,
            KairosIdFactory ids,
            Clock clock,
            JsonMapper json,
            MeterRegistry meters) {
        this.cells = cells;
        this.policies = policies;
        this.decider = decider;
        this.workflows = workflows;
        this.observers = List.copyOf(observers);
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
        this.meters = meters;
    }

    @Override
    public void settle(List<Result> results) {
        if (results.size() > 1000) throw new IllegalArgumentException("A result batch has at most 1000 records");
        results.forEach(ResultMetadata::requireValid);
        Map<Cell, List<Result>> grouped = new LinkedHashMap<>();
        for (Result result : results)
            grouped.computeIfAbsent(cells.forShard(result.getShard()), ignored -> new ArrayList<>())
                    .add(result);
        for (var entry : grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Cell::id)))
                .toList()) {
            List<Settled> committed =
                    entry.getKey().transactions().execute(jdbc -> settleCell(entry.getKey(), entry.getValue()));
            if (committed.isEmpty()) continue;
            for (Settled settled : committed)
                meters.counter(
                                "kairos.state.results",
                                "outcome",
                                settled.deferred()
                                        ? "DEFERRED"
                                        : Objects.requireNonNull(settled.outcome())
                                                .name())
                        .increment();
            for (Observer observer : observers) {
                try {
                    observer.committed(committed);
                } catch (RuntimeException failure) {
                    meters.counter("kairos.state.observer.failures").increment();
                }
            }
        }
    }

    private List<Settled> settleCell(Cell cell, List<Result> results) {
        JdbcClient jdbc = cell.jdbc();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<UUID> runIds = results.stream()
                .map(r -> ProtoIds.toUuid(r.getRunId()))
                .distinct()
                .toList();
        List<Integer> shards = results.stream().map(Result::getShard).distinct().toList();
        List<LockedRun> initial = jdbc.sql(
                        "SELECT * FROM scheduler.run_inflight WHERE shard_id IN (:shards) AND run_id IN (:ids)")
                .param("shards", shards)
                .param("ids", runIds)
                .query(this::row)
                .list();
        List<UUID> jobIds = initial.stream()
                .map(LockedRun::run)
                .filter(r -> r.targetType() == TargetType.JOB)
                .map(InflightRun::definitionId)
                .distinct()
                .sorted()
                .toList();
        List<UUID> workflowRuns = initial.stream()
                .map(LockedRun::run)
                .filter(r -> r.targetType() == TargetType.WORKFLOW_TASK)
                .map(InflightRun::targetId)
                .distinct()
                .sorted()
                .toList();
        Map<UUID, JobContext> jobs = new HashMap<>();
        if (!jobIds.isEmpty())
            jdbc.sql(
                            "SELECT job_id,version,status,spec::text FROM scheduler.jobs WHERE job_id IN (:ids) ORDER BY job_id FOR UPDATE")
                    .param("ids", jobIds)
                    .query((rs, n) -> {
                        jobs.put(
                                rs.getObject(1, UUID.class),
                                new JobContext(rs.getInt(2), rs.getString(3), json.readTree(rs.getString(4))));
                        return 1;
                    })
                    .list();
        if (!workflowRuns.isEmpty()) {
            List<UUID> parents = jdbc.sql(
                            "SELECT workflow_run_id FROM scheduler.workflow_runs WHERE workflow_run_id IN (:ids) ORDER BY workflow_run_id FOR UPDATE")
                    .param("ids", workflowRuns)
                    .query(UUID.class)
                    .list();
            if (parents.size() != workflowRuns.size())
                throw new IllegalStateException("Result workflow run is missing");
        }
        Map<UUID, LockedRun> locked = new HashMap<>();
        jdbc.sql(
                        "SELECT * FROM scheduler.run_inflight WHERE shard_id IN (:shards) AND run_id IN (:ids) ORDER BY run_id FOR UPDATE")
                .param("shards", shards)
                .param("ids", runIds)
                .query((rs, n) -> {
                    LockedRun run = row(rs, n);
                    locked.put(run.run().runId().value(), run);
                    return 1;
                })
                .list();
        SettlementPolicyLoader.Session policy = policies.session(cell);
        List<Work> work = new ArrayList<>();
        for (Result result : results) {
            UUID id = ProtoIds.toUuid(result.getRunId());
            LockedRun current = locked.get(id);
            if (current == null) continue;
            InflightRun run = current.run();
            if (run.state() != RunState.RUNNING
                    || run.fence().value() != result.getFence()
                    || run.attempt() != result.getAttempt()
                    || run.infraAttempt() != result.getInfraAttempt()) continue;
            if (run.targetType() == TargetType.JOB && !jobs.containsKey(run.definitionId()))
                throw new IllegalStateException("Result definition is missing");
            RunPolicy resolved = policy.load(run);
            work.add(new Work(run, result, resolved, decider.decide(run, current.cancelRequested(), result, resolved)));
            locked.remove(id);
        }
        cell.database()
                .batchUpdate(
                        HISTORY,
                        work.stream().filter(w -> !w.decision().deferred()).toList(),
                        this::history);
        List<OutboxMessage> messages = new ArrayList<>();
        List<Settled> settled = new ArrayList<>();
        for (Work item : work) {
            InflightRun run = item.run();
            Result result = item.result();
            var decision = item.decision();
            if (decision.deferred() || decision.outcome() == Outcome.FAILED_RETRYING) {
                jdbc.sql("""
                        UPDATE scheduler.run_inflight SET state=4,due_at=:due,attempt=:attempt,fence=fence+1,
                            infra_attempt=:infra,deferrals=CASE WHEN :deferred THEN deferrals+1 ELSE 0 END,owner_node=NULL,lease_until=NULL
                        WHERE shard_id=:shard AND run_id=:id AND fence=:fence AND state=3
                        """)
                        .param("due", utc(Objects.requireNonNull(decision.dueAt())))
                        .param("attempt", decision.nextAttempt())
                        .param("infra", decision.deferred() ? run.infraAttempt() : 0)
                        .param("deferred", decision.deferred())
                        .param("shard", run.shard().value())
                        .param("id", run.runId().value())
                        .param("fence", run.fence().value())
                        .update();
            } else {
                if (decision.outcome() == Outcome.DEAD_LETTERED) messages.add(dead(cell, item, now));
                jdbc.sql("DELETE FROM scheduler.run_inflight WHERE shard_id=:shard AND run_id=:id AND fence=:fence")
                        .param("shard", run.shard().value())
                        .param("id", run.runId().value())
                        .param("fence", run.fence().value())
                        .update();
                if (item.policy().completedEvents()) messages.add(completed(cell, item));
                if (run.targetType() == TargetType.JOB) jobOutcome(cell, item, jobs.get(run.definitionId()), now);
            }
            if (!decision.deferred() && run.targetType() == TargetType.WORKFLOW_TASK)
                workflows.settled(run, result, Objects.requireNonNull(decision.outcome()), now);
            settled.add(new Settled(run, result, decision.outcome(), decision.deferred()));
        }
        outbox.write(messages);
        return List.copyOf(settled);
    }

    private MapSqlParameterSource history(Work work) {
        InflightRun run = work.run();
        Result result = work.result();
        var decision = work.decision();
        return new MapSqlParameterSource()
                .addValue("scheduled", utc(run.scheduledAt()))
                .addValue("run", run.runId().value())
                .addValue("attempt", run.attempt())
                .addValue("infra", run.infraAttempt())
                .addValue("team", run.teamId().value())
                .addValue("type", run.targetType().code())
                .addValue("definition", run.definitionId())
                .addValue("target", run.targetId())
                .addValue("task", run.taskKeyColumn())
                .addValue("endpoint", run.endpointId().value())
                .addValue("trigger", run.trigger().code())
                .addValue("outcome", Objects.requireNonNull(decision.outcome()).code())
                .addValue(
                        "errorClass",
                        decision.error() == null ? null : decision.error().code(),
                        Types.SMALLINT)
                .addValue("final", decision.terminal())
                .addValue("started", utc(ProtoTimestamps.toInstant(result.getStartedAt())))
                .addValue("finished", utc(ProtoTimestamps.toInstant(result.getFinishedAt())))
                .addValue("http", result.getHttpStatus() == 0 ? null : result.getHttpStatus(), Types.SMALLINT)
                .addValue("latency", result.getLatencyMs())
                .addValue("executor", result.getExecutorId())
                .addValue("error", result.getError().isEmpty() ? null : result.getError(), Types.VARCHAR)
                .addValue(
                        "snippet",
                        result.getResponseSnippet().isEmpty() ? null : result.getResponseSnippet(),
                        Types.VARCHAR);
    }

    private OutboxMessage dead(Cell cell, Work item, Instant now) {
        InflightRun run = item.run();
        Result result = item.result();
        ErrorClass reason = Objects.requireNonNull(item.decision().error());
        UUID proposed = ids.newDlqId(run.shard()).value();
        UUID dlq = cell.jdbc()
                .sql("""
                INSERT INTO scheduler.dead_letters(dlq_id,team_id,run_id,shard_id,scheduled_at,target_type,definition_id,
                    definition_version,target_id,task_key,endpoint_id,endpoint_version,attempt,infra_attempt,priority,reason,
                    last_http_status,last_error,response_snippet,status,created_at,fence)
                VALUES (:dlq,:team,:run,:shard,:scheduled,:type,:definition,:version,:target,:task,:endpoint,:endpointVersion,
                    :attempt,:infra,:priority,:reason,:http,:error,:snippet,1,:now,:fence)
                ON CONFLICT(run_id) DO UPDATE SET reason=EXCLUDED.reason,attempt=EXCLUDED.attempt,infra_attempt=EXCLUDED.infra_attempt,
                    fence=EXCLUDED.fence,last_http_status=EXCLUDED.last_http_status,last_error=EXCLUDED.last_error,
                    response_snippet=EXCLUDED.response_snippet,status=1,created_at=EXCLUDED.created_at,
                    resolved_by=NULL,resolved_at=NULL,redrive_mode=NULL,assignee=NULL,notes=NULL
                RETURNING dlq_id
                """)
                .param("dlq", proposed)
                .param("team", run.teamId().value())
                .param("run", run.runId().value())
                .param("shard", run.shard().value())
                .param("scheduled", utc(run.scheduledAt()))
                .param("type", run.targetType().code())
                .param("definition", run.definitionId())
                .param("version", run.definitionVersion())
                .param("target", run.targetId())
                .param("task", run.taskKeyColumn())
                .param("endpoint", run.endpointId().value())
                .param("endpointVersion", run.endpointVersion())
                .param("attempt", run.attempt())
                .param("infra", run.infraAttempt())
                .param("priority", run.priority().value())
                .param("reason", reason.code())
                .param("http", result.getHttpStatus() == 0 ? null : result.getHttpStatus(), Types.SMALLINT)
                .param("error", result.getError().isEmpty() ? null : result.getError(), Types.VARCHAR)
                .param(
                        "snippet",
                        result.getResponseSnippet().isEmpty() ? null : result.getResponseSnippet(),
                        Types.VARCHAR)
                .param("now", utc(now))
                .param("fence", run.fence().value())
                .query(UUID.class)
                .single();
        Dead message = Dead.newBuilder()
                .setDlqId(ProtoIds.toBytes(dlq))
                .setRunId(ProtoIds.toBytes(run.runId().value()))
                .setShard(run.shard().value())
                .setTeamId(ProtoIds.toBytes(run.teamId().value()))
                .setTargetTypeValue(run.targetType().code())
                .setDefinitionId(ProtoIds.toBytes(run.definitionId()))
                .setDefinitionVersion(run.definitionVersion())
                .setTargetId(ProtoIds.toBytes(run.targetId()))
                .setTaskKey(run.taskKeyColumn())
                .setEndpointId(ProtoIds.toBytes(run.endpointId().value()))
                .setEndpointVersion(run.endpointVersion())
                .setPriority(run.priority().value())
                .setScheduledAt(ProtoTimestamps.toTimestamp(run.scheduledAt()))
                .setAttempt(run.attempt())
                .setInfraAttempt(run.infraAttempt())
                .setReasonValue(reason.code())
                .setLastHttpStatus(result.getHttpStatus())
                .setDeadAt(ProtoTimestamps.toTimestamp(now))
                .build();
        return OutboxMessage.dead(message, now, null);
    }

    private OutboxMessage completed(Cell cell, Work item) {
        InflightRun run = item.run();
        Result result = item.result();
        Instant started = cell.jdbc()
                .sql("SELECT min(started_at) FROM scheduler.run_history WHERE run_id=:id")
                .param("id", run.runId().value())
                .query((rs, row) -> {
                    var value = rs.getTimestamp(1);
                    return value == null ? ProtoTimestamps.toInstant(result.getStartedAt()) : value.toInstant();
                })
                .single();
        Completed message = Completed.newBuilder()
                .setRunId(ProtoIds.toBytes(run.runId().value()))
                .setShard(run.shard().value())
                .setTeamId(ProtoIds.toBytes(run.teamId().value()))
                .setTargetTypeValue(run.targetType().code())
                .setDefinitionId(ProtoIds.toBytes(run.definitionId()))
                .setDefinitionVersion(run.definitionVersion())
                .setTargetId(ProtoIds.toBytes(run.targetId()))
                .setTaskKey(run.taskKeyColumn())
                .setTriggerValue(run.trigger().code())
                .setScheduledAt(ProtoTimestamps.toTimestamp(run.scheduledAt()))
                .setOutcomeValue(
                        Objects.requireNonNull(item.decision().outcome()).code())
                .setErrorClassValue(
                        item.decision().error() == null
                                ? 0
                                : item.decision().error().code())
                .setHttpStatus(result.getHttpStatus())
                .setAttempts(run.attempt())
                .setStartedAt(ProtoTimestamps.toTimestamp(started))
                .setFinishedAt(result.getFinishedAt())
                .setLatencyMs(result.getLatencyMs())
                .build();
        return OutboxMessage.completed(message, ProtoTimestamps.toInstant(result.getFinishedAt()), null);
    }

    private void jobOutcome(Cell cell, Work item, JobContext job, Instant now) {
        InflightRun run = item.run();
        Outcome outcome = Objects.requireNonNull(item.decision().outcome());
        JdbcClient jdbc = cell.jdbc();
        if (outcome == Outcome.SUCCEEDED || outcome == Outcome.DEAD_LETTERED) {
            jdbc.sql(
                            "INSERT INTO scheduler.job_health(job_id,team_id,recent_mask,recent_count,consecutive_dead,updated_at) VALUES (:job,:team,0,0,0,:now) ON CONFLICT(job_id) DO NOTHING")
                    .param("job", run.definitionId())
                    .param("team", run.teamId().value())
                    .param("now", utc(now))
                    .update();
            JobHealth before = jdbc.sql("SELECT * FROM scheduler.job_health WHERE job_id=:job FOR UPDATE")
                    .param("job", run.definitionId())
                    .query((rs, row) -> new JobHealth(
                            rs.getInt("recent_mask"),
                            rs.getInt("recent_count"),
                            rs.getInt("consecutive_dead"),
                            instant(rs, "unhealthy_since")))
                    .single();
            JobHealth after = before.withOutcome(outcome, now);
            jdbc.sql(
                            "UPDATE scheduler.job_health SET recent_mask=:mask,recent_count=:count,consecutive_dead=:dead,unhealthy_since=:unhealthy,updated_at=:now WHERE job_id=:job")
                    .param("mask", after.recentMask())
                    .param("count", after.recentCount())
                    .param("dead", after.consecutiveDead())
                    .param("unhealthy", after.unhealthySince() == null ? null : utc(after.unhealthySince()))
                    .param("now", utc(now))
                    .param("job", run.definitionId())
                    .update();
            String kind = job.spec().path("schedule").path("type").asString();
            if ((kind.equals("CRON") || kind.equals("INTERVAL"))
                    && new AutoPause(job.spec().path("autoPauseAfter").asInt(3)).shouldPause(outcome, after)) {
                int paused = jdbc.sql(
                                "UPDATE scheduler.jobs SET status='AUTO_PAUSED',updated_at=:now WHERE job_id=:job AND status='ACTIVE'")
                        .param("now", utc(now))
                        .param("job", run.definitionId())
                        .update();
                if (paused == 1) {
                    jdbc.sql("UPDATE scheduler.schedules SET status=2 WHERE schedule_id=:job")
                            .param("job", run.definitionId())
                            .update();
                    jdbc.sql(
                                    "INSERT INTO scheduler.settlement_audits(audit_id,at,team_id,job_id,consecutive_dead) VALUES (:id,:at,:team,:job,:dead)")
                            .param("id", ids.newId(run.shard()))
                            .param("at", utc(now))
                            .param("team", run.teamId().value())
                            .param("job", run.definitionId())
                            .param("dead", after.consecutiveDead())
                            .update();
                }
            }
        }
        String kind = job.spec().path("schedule").path("type").asString();
        boolean original = (kind.equals("ONE_TIME") && run.trigger() == Trigger.SCHEDULE)
                || (kind.equals("IMMEDIATE") && run.trigger() == Trigger.IMMEDIATE);
        if (original && job.version() == run.definitionVersion()) {
            String status = switch (outcome) {
                case SUCCEEDED, SKIPPED -> "COMPLETED";
                case CANCELLED -> "CANCELLED";
                default -> "FAILED";
            };
            jdbc.sql(
                            "UPDATE scheduler.jobs SET status=:status,updated_at=:now WHERE job_id=:job AND version=:version AND status IN ('ACTIVE','PAUSED')")
                    .param("status", status)
                    .param("now", utc(now))
                    .param("job", run.definitionId())
                    .param("version", run.definitionVersion())
                    .update();
        }
    }

    private LockedRun row(ResultSet rs, int index) throws SQLException {
        String key = rs.getString("task_key");
        InflightRun run = new InflightRun(
                new RunId(rs.getObject("run_id", UUID.class)),
                new TeamId(rs.getObject("team_id", UUID.class)),
                TargetType.fromCode(rs.getInt("target_type")),
                rs.getObject("definition_id", UUID.class),
                rs.getInt("definition_version"),
                rs.getObject("target_id", UUID.class),
                key.isEmpty() ? null : TaskKey.of(key),
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
        return new LockedRun(run, rs.getBoolean("cancel_requested"));
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record LockedRun(InflightRun run, boolean cancelRequested) {}

    private record JobContext(int version, String status, JsonNode spec) {}

    private record Work(InflightRun run, Result result, RunPolicy policy, ResultDecider.Decision decision) {}
}
