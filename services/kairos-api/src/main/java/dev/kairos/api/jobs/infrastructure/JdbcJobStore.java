package dev.kairos.api.jobs.infrastructure;

import com.google.protobuf.Timestamp;
import dev.kairos.api.jobs.application.JobEndpointCatalog;
import dev.kairos.api.jobs.application.JobEndpointCatalog.Endpoint;
import dev.kairos.api.jobs.application.JobSchedule;
import dev.kairos.api.jobs.application.JobSpec;
import dev.kairos.api.jobs.application.JobStore;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.NotFoundException;
import dev.kairos.domain.error.PreconditionFailedException;
import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.TargetType;
import dev.kairos.engine.outbox.OutboxMessage;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.engine.run.CallSpec;
import dev.kairos.engine.run.InflightRun;
import dev.kairos.engine.run.PlannedRun;
import dev.kairos.engine.run.RunCreator;
import dev.kairos.engine.run.RunFactory;
import dev.kairos.engine.run.RunTarget;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Cancel;
import dev.kairos.proto.v1.CancelReason;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cell SQL adapter for the job API. All definition, schedule and run mutations share a cell transaction. */
public final class JdbcJobStore implements JobStore {
    private static final String FIND = """
            SELECT j.job_id, j.name, j.status, j.version, j.spec::text AS spec, j.created_at, j.updated_at,
                   s.next_fire_at,
                   last_run.run_id AS last_run_id, last_run.status AS last_run_status,
                   last_run.finished_at AS last_run_finished_at
              FROM scheduler.jobs j
              LEFT JOIN scheduler.schedules s ON s.schedule_id = j.job_id
              LEFT JOIN LATERAL (
                   SELECT r.run_id, r.status, r.finished_at
                     FROM (
                       SELECT i.run_id,
                              CASE WHEN i.cancel_requested THEN 'CANCELLED'
                                   ELSE CASE i.state WHEN 1 THEN 'WAITING' WHEN 2 THEN 'QUEUED'
                                        WHEN 3 THEN 'RUNNING' WHEN 4 THEN 'DELAYED' END END AS status,
                              i.scheduled_at, h.finished_at
                         FROM scheduler.run_inflight i
                         LEFT JOIN LATERAL (SELECT finished_at FROM scheduler.run_history h
                                              WHERE h.run_id=i.run_id ORDER BY h.attempt DESC, h.infra_attempt DESC LIMIT 1) h ON true
                        WHERE i.team_id=j.team_id AND i.target_id=j.job_id
                       UNION ALL
                       SELECT h.run_id,
                              CASE h.outcome WHEN 1 THEN 'SUCCEEDED' WHEN 3 THEN 'DEAD_LETTERED'
                                   WHEN 4 THEN 'CANCELLED' WHEN 5 THEN 'SKIPPED' WHEN 6 THEN 'UPSTREAM_FAILED' END,
                              h.scheduled_at, h.finished_at
                         FROM scheduler.run_history h
                        WHERE h.team_id=j.team_id AND h.target_id=j.job_id AND h.final
                     ) r
                    ORDER BY r.scheduled_at DESC, r.run_id DESC LIMIT 1
              ) last_run ON true
             WHERE j.team_id=:team AND j.job_id=:id
            """;

    private static final String LOCK = """
            SELECT job_id, team_id, name, version, status, endpoint_id, spec::text
              FROM scheduler.jobs WHERE team_id=:team AND job_id=:id FOR UPDATE
            """;

    private static final String INSERT_JOB = """
            INSERT INTO scheduler.jobs(job_id, team_id, name, version, status, endpoint_id, spec, created_by, created_at, updated_at)
            VALUES (:id, :team, :name, 1, :status, :endpoint, CAST(:spec AS jsonb), :actor, :now, :now)
            """;

    private static final String INSERT_VERSION = """
            INSERT INTO scheduler.job_versions(job_id, version, spec, created_at)
            VALUES (:id, :version, CAST(:spec AS jsonb), :now)
            """;

    private static final String UPSERT_SCHEDULE = """
            INSERT INTO scheduler.schedules(
                schedule_id,target_type,team_id,endpoint_id,shard_id,status,next_fire_at,kind,cron_expr,timezone,
                interval_ms,spread_ms,dst_policy,misfire_policy,max_catchup,definition_version,priority,last_fired_local)
            VALUES (:id,1,:team,:endpoint,:shard,:status,:next,:kind,:cron,:timezone,:interval,:spread,:dst,:misfire,
                    :catchup,:version,:priority,NULL)
            ON CONFLICT(schedule_id) DO UPDATE SET endpoint_id=EXCLUDED.endpoint_id,status=EXCLUDED.status,
                next_fire_at=EXCLUDED.next_fire_at,kind=EXCLUDED.kind,cron_expr=EXCLUDED.cron_expr,
                timezone=EXCLUDED.timezone,interval_ms=EXCLUDED.interval_ms,spread_ms=EXCLUDED.spread_ms,
                dst_policy=EXCLUDED.dst_policy,misfire_policy=EXCLUDED.misfire_policy,max_catchup=EXCLUDED.max_catchup,
                definition_version=EXCLUDED.definition_version,priority=EXCLUDED.priority,last_fired_local=NULL
            """;

    private static final String INSERT_CANCEL_HISTORY = """
            INSERT INTO scheduler.run_history(scheduled_at,run_id,attempt,infra_attempt,team_id,target_type,
                definition_id,target_id,task_key,endpoint_id,trigger,outcome,error_class,final,finished_at)
            VALUES (:scheduled,:run,:attempt,:infra,:team,1,:job,:job,'',:endpoint,:trigger,4,NULL,true,:finished)
            ON CONFLICT DO NOTHING
            """;

    private final CellRouter cells;
    private final RunFactory runFactory;
    private final RunCreator runCreator;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final JsonMapper json;

    public JdbcJobStore(
            CellRouter cells,
            RunFactory runFactory,
            RunCreator runCreator,
            OutboxWriter outbox,
            Clock clock,
            JsonMapper json) {
        this.cells = Objects.requireNonNull(cells, "cells");
        this.runFactory = Objects.requireNonNull(runFactory, "runFactory");
        this.runCreator = Objects.requireNonNull(runCreator, "runCreator");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public Optional<Job> find(TeamId team, UUID id) {
        return cells.forId(id)
                .jdbc()
                .sql(FIND)
                .param("team", team.value())
                .param("id", id)
                .query((rs, row) -> job(rs, json))
                .optional();
    }

    @Override
    public List<Job> list(TeamId team, Filter filter, @Nullable Key after, int limit) {
        List<Job> rows = cells.allCells().parallelStream()
                .map(cell -> listCell(cell, team, filter, after, limit))
                .flatMap(List::stream)
                .sorted(Comparator.comparing(Job::createdAt)
                        .thenComparing(Job::id)
                        .reversed())
                .limit(limit)
                .toList();
        return List.copyOf(rows);
    }

    private List<Job> listCell(Cell cell, TeamId team, Filter filter, @Nullable Key after, int limit) {
        StringBuilder sql = new StringBuilder(FIND.replace(
                "WHERE j.team_id=:team AND j.job_id=:id", "WHERE j.team_id=:team AND j.status <> 'DELETED'"));
        if (filter.status() != null) sql.append(" AND j.status=:status");
        if (filter.endpoint() != null) sql.append(" AND j.spec->>'endpoint'=:endpoint");
        if (filter.namePrefix() != null) sql.append(" AND left(j.name,length(:prefix))=:prefix");
        if (after != null) sql.append(" AND (j.created_at,j.job_id)<(:after,:afterId)");
        sql.append(" ORDER BY j.created_at DESC,j.job_id DESC LIMIT :limit");
        JdbcClient.StatementSpec query =
                cell.jdbc().sql(sql.toString()).param("team", team.value()).param("limit", limit);
        if (filter.status() != null) query = query.param("status", filter.status());
        if (filter.endpoint() != null) query = query.param("endpoint", filter.endpoint());
        if (filter.namePrefix() != null) query = query.param("prefix", filter.namePrefix());
        if (after != null) query = query.param("after", utc(after.createdAt())).param("afterId", after.id());
        return query.query((rs, row) -> job(rs, json)).list();
    }

    @Override
    public Job create(
            TeamId team,
            UUID id,
            String actor,
            JobSpec spec,
            JobEndpointCatalog.Endpoint endpoint,
            JobSchedule schedule,
            Instant now,
            @Nullable CallSpec immediateCall) {
        Cell cell = cells.forId(id);
        try {
            cell.transactions().execute(jdbc -> {
                try {
                    jdbc.sql(INSERT_JOB)
                            .param("id", id)
                            .param("team", team.value())
                            .param("name", spec.name())
                            .param("status", "ACTIVE")
                            .param("endpoint", endpoint.id())
                            .param("spec", specJson(spec))
                            .param("actor", actor)
                            .param("now", utc(now))
                            .update();
                } catch (DuplicateKeyException duplicate) {
                    throw new ConflictException(
                            ErrorCode.JOB_NAME_TAKEN, "A job with this name already exists in the team.");
                }
                jdbc.sql(INSERT_VERSION)
                        .param("id", id)
                        .param("version", 1)
                        .param("spec", specJson(spec))
                        .param("now", utc(now))
                        .update();
                if (schedule.kind() != null)
                    saveSchedule(
                            jdbc,
                            id,
                            team,
                            endpoint,
                            schedule,
                            1,
                            spec.priority(),
                            "ACTIVE".equals(endpoint.status()) ? "ACTIVE" : "HELD");
                if (immediateCall != null) {
                    RunTarget target = target(team, id, 1, endpoint, spec, immediateCall);
                    runCreator.create(runFactory.immediate(target, now), null);
                }
                return null;
            });
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            if (isNameConflict(exception))
                throw new ConflictException(
                        ErrorCode.JOB_NAME_TAKEN, "A job with this name already exists in the team.");
            throw exception;
        }
        return find(team, id).orElseThrow(() -> new IllegalStateException("Created job is missing"));
    }

    @Override
    public Job update(
            TeamId team,
            UUID id,
            long expectedVersion,
            JobSpec spec,
            JobEndpointCatalog.Endpoint endpoint,
            JobSchedule schedule,
            Instant now) {
        Cell cell = cells.forId(id);
        cell.transactions().execute(jdbc -> {
            LockedJob current = lock(jdbc, team, id);
            if (current.version() != expectedVersion) throw PreconditionFailedException.versionMismatch();
            if ("DELETED".equals(current.status()) || "CANCELLED".equals(current.status())) {
                throw new ConflictException(ErrorCode.INVALID_STATE, "A cancelled or deleted job cannot be updated.");
            }
            int version = current.version() + 1;
            int changed = jdbc.sql("""
                    UPDATE scheduler.jobs SET version=:version,endpoint_id=:endpoint,spec=CAST(:spec AS jsonb),updated_at=:now
                     WHERE team_id=:team AND job_id=:id AND version=:expected
                    """)
                    .param("version", version)
                    .param("endpoint", endpoint.id())
                    .param("spec", specJson(spec))
                    .param("now", utc(now))
                    .param("team", team.value())
                    .param("id", id)
                    .param("expected", expectedVersion)
                    .update();
            if (changed != 1) throw PreconditionFailedException.versionMismatch();
            jdbc.sql(INSERT_VERSION)
                    .param("id", id)
                    .param("version", version)
                    .param("spec", specJson(spec))
                    .param("now", utc(now))
                    .update();
            if (schedule.kind() == null) {
                jdbc.sql("DELETE FROM scheduler.schedules WHERE schedule_id=:id")
                        .param("id", id)
                        .update();
            } else {
                String scheduleStatus = "PAUSED".equals(current.status()) || "AUTO_PAUSED".equals(current.status())
                        ? "PAUSED"
                        : "ACTIVE";
                if (!"ACTIVE".equals(endpoint.status())) scheduleStatus = "HELD";
                saveSchedule(jdbc, id, team, endpoint, schedule, version, spec.priority(), scheduleStatus);
            }
            return null;
        });
        return find(team, id).orElseThrow(() -> new IllegalStateException("Updated job is missing"));
    }

    @Override
    public Job transition(
            TeamId team,
            UUID id,
            Transition transition,
            JobSchedule schedule,
            @Nullable Endpoint endpoint,
            Instant now) {
        Cell cell = cells.forId(id);
        cell.transactions().execute(jdbc -> {
            LockedJob current = lock(jdbc, team, id);
            switch (transition) {
                case PAUSE -> {
                    if (!"ACTIVE".equals(current.status()))
                        throw new ConflictException(ErrorCode.INVALID_STATE, "Only an ACTIVE job can be paused.");
                    jdbc.sql(
                                    "UPDATE scheduler.jobs SET status='PAUSED',updated_at=:now WHERE job_id=:id AND team_id=:team")
                            .param("now", utc(now))
                            .param("id", id)
                            .param("team", team.value())
                            .update();
                    jdbc.sql("UPDATE scheduler.schedules SET status=2 WHERE schedule_id=:id")
                            .param("id", id)
                            .update();
                }
                case RESUME -> {
                    if (!List.of("PAUSED", "AUTO_PAUSED").contains(current.status()))
                        throw new ConflictException(
                                ErrorCode.INVALID_STATE, "Only a PAUSED or AUTO_PAUSED job can be resumed.");
                    jdbc.sql(
                                    "UPDATE scheduler.jobs SET status='ACTIVE',updated_at=:now WHERE job_id=:id AND team_id=:team")
                            .param("now", utc(now))
                            .param("id", id)
                            .param("team", team.value())
                            .update();
                    if (schedule.kind() != null) {
                        String scheduleStatus =
                                endpoint != null && !"ACTIVE".equals(endpoint.status()) ? "HELD" : "ACTIVE";
                        saveSchedule(
                                jdbc,
                                id,
                                team,
                                Objects.requireNonNull(endpoint, "endpoint"),
                                schedule,
                                current.version(),
                                priority(current.spec()),
                                scheduleStatus);
                    }
                }
                case CANCEL -> {
                    if ("CANCELLED".equals(current.status()) || "DELETED".equals(current.status())) {
                        throw new ConflictException(ErrorCode.INVALID_STATE, "The job is already cancelled.");
                    }
                    cancelRuns(jdbc, team, id, now);
                    jdbc.sql(
                                    "UPDATE scheduler.jobs SET status='CANCELLED',updated_at=:now WHERE job_id=:id AND team_id=:team")
                            .param("now", utc(now))
                            .param("id", id)
                            .param("team", team.value())
                            .update();
                    jdbc.sql("DELETE FROM scheduler.schedules WHERE schedule_id=:id")
                            .param("id", id)
                            .update();
                }
            }
            return null;
        });
        return find(team, id).orElseThrow(() -> new IllegalStateException("Transitioned job is missing"));
    }

    @Override
    public void delete(TeamId team, UUID id, Instant now) {
        Cell cell = cells.forId(id);
        cell.transactions().execute(jdbc -> {
            lock(jdbc, team, id);
            cancelRuns(jdbc, team, id, now);
            jdbc.sql("DELETE FROM scheduler.schedules WHERE schedule_id=:id")
                    .param("id", id)
                    .update();
            jdbc.sql("UPDATE scheduler.jobs SET status='DELETED',updated_at=:now WHERE job_id=:id AND team_id=:team")
                    .param("now", utc(now))
                    .param("id", id)
                    .param("team", team.value())
                    .update();
            return null;
        });
    }

    @Override
    public RunResult runNow(
            TeamId team,
            UUID id,
            long expectedVersion,
            JobSpec spec,
            JobEndpointCatalog.Endpoint endpoint,
            JobSpec.Request request,
            CallSpec call,
            Instant now) {
        Cell cell = cells.forId(id);
        return cell.transactions().execute(jdbc -> {
            LockedJob current = lock(jdbc, team, id);
            if (current.version() != expectedVersion) throw PreconditionFailedException.versionMismatch();
            if (List.of("CANCELLED", "DELETED").contains(current.status()))
                throw new ConflictException(ErrorCode.INVALID_STATE, "A cancelled job cannot be run.");
            RunTarget target = target(team, id, current.version(), endpoint, spec, call);
            var created =
                    runCreator.create(runFactory.manual(target, now), null).created();
            if (created.isEmpty()) throw new IllegalStateException("Manual run was not inserted");
            return new RunResult(
                    created.getFirst().runId().value(),
                    created.getFirst().status().name());
        });
    }

    @Override
    public BackfillResult backfill(
            TeamId team,
            UUID id,
            long expectedVersion,
            JobSpec spec,
            JobEndpointCatalog.Endpoint endpoint,
            List<Instant> occurrences,
            CallSpec call,
            int ratePerSecond) {
        if (occurrences.isEmpty()) return new BackfillResult(0, null, null);
        Cell cell = cells.forId(id);
        return cell.transactions().execute(jdbc -> {
            LockedJob current = lock(jdbc, team, id);
            if (current.version() != expectedVersion) throw PreconditionFailedException.versionMismatch();
            RunTarget target = target(team, id, current.version(), endpoint, spec, call);
            Instant first = occurrences.getFirst();
            Instant last = occurrences.getLast();
            Set<Instant> existing = new HashSet<>(jdbc.sql("""
                    SELECT scheduled_at FROM scheduler.run_inflight WHERE team_id=:team AND target_id=:id
                     AND scheduled_at>=:first AND scheduled_at<=:last
                    UNION SELECT scheduled_at FROM scheduler.run_history WHERE team_id=:team AND target_id=:id
                     AND scheduled_at>=:first AND scheduled_at<=:last
                    """)
                    .param("team", team.value())
                    .param("id", id)
                    .param("first", utc(first))
                    .param("last", utc(last))
                    .query((rs, row) -> rs.getTimestamp(1).toInstant())
                    .list());
            List<Instant> missing = occurrences.stream()
                    .filter(value -> !existing.contains(value))
                    .toList();
            List<PlannedRun> planned = new ArrayList<>(missing.size());
            for (int offset = 0; offset < missing.size(); offset += RunFactory.MAX_BACKFILL_OCCURRENCES) {
                List<Instant> part =
                        missing.subList(offset, Math.min(missing.size(), offset + RunFactory.MAX_BACKFILL_OCCURRENCES));
                for (PlannedRun value : runFactory.backfill(target, part, ratePerSecond)) {
                    Instant due = value.run().dueAt().plusNanos((long) offset * 1_000_000_000L / ratePerSecond);
                    planned.add(new PlannedRun(withDueAt(value.run(), due), value.call()));
                }
            }
            var result = runCreator.create(planned, null);
            List<InflightRun> inserted = result.created();
            return new BackfillResult(
                    inserted.size(),
                    inserted.isEmpty() ? null : inserted.getFirst().scheduledAt(),
                    inserted.isEmpty() ? null : inserted.getLast().scheduledAt());
        });
    }

    @Override
    public List<JobRun> runs(TeamId team, UUID id, RunFilter filter, @Nullable RunKey after, int limit) {
        Cell cell = cells.forId(id);
        StringBuilder sql = new StringBuilder("""
                WITH records AS (
                    SELECT i.run_id,i.target_id AS job_id,
                           CASE WHEN i.cancel_requested THEN 'CANCELLED' ELSE CASE i.state
                                WHEN 1 THEN 'WAITING' WHEN 2 THEN 'QUEUED' WHEN 3 THEN 'RUNNING' WHEN 4 THEN 'DELAYED' END END AS status,
                           i.attempt,i.scheduled_at,h.finished_at,h.http_status,h.latency_ms
                      FROM scheduler.run_inflight i
                      LEFT JOIN LATERAL (SELECT finished_at,http_status,latency_ms FROM scheduler.run_history
                                           WHERE run_id=i.run_id ORDER BY attempt DESC,infra_attempt DESC LIMIT 1) h ON true
                     WHERE i.team_id=:team AND i.target_id=:job
                    UNION ALL
                    SELECT h.run_id,h.target_id AS job_id,
                           CASE h.outcome WHEN 1 THEN 'SUCCEEDED' WHEN 3 THEN 'DEAD_LETTERED'
                                WHEN 4 THEN 'CANCELLED' WHEN 5 THEN 'SKIPPED' WHEN 6 THEN 'UPSTREAM_FAILED' END,
                           h.attempt,h.scheduled_at,h.finished_at,h.http_status,h.latency_ms
                      FROM scheduler.run_history h WHERE h.team_id=:team AND h.target_id=:job AND h.final
                ) SELECT run_id,job_id,status,attempt,scheduled_at,finished_at,http_status,latency_ms FROM records WHERE true
                """);
        if (filter.status() != null) sql.append(" AND status=:status");
        if (filter.from() != null) sql.append(" AND scheduled_at>=:from");
        if (filter.to() != null) sql.append(" AND scheduled_at<=:to");
        if (after != null) sql.append(" AND (scheduled_at,run_id)<(:after,:afterId)");
        sql.append(" ORDER BY scheduled_at DESC,run_id DESC LIMIT :limit");
        JdbcClient.StatementSpec query = cell.jdbc()
                .sql(sql.toString())
                .param("team", team.value())
                .param("job", id)
                .param("limit", limit);
        if (filter.status() != null) query = query.param("status", filter.status());
        if (filter.from() != null) query = query.param("from", utc(filter.from()));
        if (filter.to() != null) query = query.param("to", utc(filter.to()));
        if (after != null)
            query = query.param("after", utc(after.scheduledAt())).param("afterId", after.runId());
        return query.query((rs, row) -> new JobRun(
                        rs.getObject("run_id", UUID.class),
                        id,
                        rs.getString("status"),
                        rs.getInt("attempt"),
                        rs.getTimestamp("scheduled_at").toInstant(),
                        instant(rs, "finished_at"),
                        nullableInteger(rs, "http_status"),
                        nullableInteger(rs, "latency_ms")))
                .list();
    }

    private void saveSchedule(
            JdbcClient jdbc,
            UUID id,
            TeamId team,
            JobEndpointCatalog.Endpoint endpoint,
            JobSchedule schedule,
            int version,
            int priority,
            String status) {
        jdbc.sql(UPSERT_SCHEDULE)
                .param("id", id)
                .param("team", team.value())
                .param("endpoint", endpoint.id())
                .param("shard", KairosId.shardOf(id).value())
                .param("status", "HELD".equals(status) ? 3 : "PAUSED".equals(status) ? 2 : 1)
                .param("next", schedule.nextFireAt() == null ? null : utc(schedule.nextFireAt()))
                .param("kind", schedule.kind() == null ? null : schedule.kind().code())
                .param("cron", schedule.cronExpression())
                .param("timezone", schedule.timezone())
                .param("interval", schedule.intervalMs())
                .param("spread", schedule.spreadMs())
                .param("dst", schedule.dstPolicy())
                .param("misfire", schedule.misfirePolicy().code())
                .param("catchup", schedule.maxCatchup())
                .param("version", version)
                .param("priority", priority)
                .update();
    }

    private void cancelRuns(JdbcClient jdbc, TeamId team, UUID jobId, Instant now) {
        int shard = KairosId.shardOf(jobId).value();
        List<CancelRun> running = jdbc.sql("""
                SELECT run_id,fence FROM scheduler.run_inflight
                 WHERE team_id=:team AND target_id=:job AND state=3 AND NOT cancel_requested FOR UPDATE
                """)
                .param("team", team.value())
                .param("job", jobId)
                .query((rs, row) -> new CancelRun(rs.getObject("run_id", UUID.class), rs.getInt("fence")))
                .list();
        jdbc.sql("""
                DELETE FROM scheduler.outbox o USING scheduler.run_inflight i
                 WHERE o.shard_id=:shard AND o.shard_id=i.shard_id AND o.run_id=i.run_id AND o.topic IN (1,2,3)
                   AND i.team_id=:team AND i.target_id=:job AND i.state IN (1,2,4)
                """)
                .param("shard", shard)
                .param("team", team.value())
                .param("job", jobId)
                .update();
        List<PendingRun> pending = jdbc.sql("""
                SELECT run_id,attempt,infra_attempt,scheduled_at,endpoint_id,trigger FROM scheduler.run_inflight
                 WHERE team_id=:team AND target_id=:job AND state IN (1,2,4) FOR UPDATE
                """)
                .param("team", team.value())
                .param("job", jobId)
                .query((rs, row) -> new PendingRun(
                        rs.getObject("run_id", UUID.class),
                        rs.getInt("attempt"),
                        rs.getInt("infra_attempt"),
                        rs.getTimestamp("scheduled_at").toInstant(),
                        rs.getObject("endpoint_id", UUID.class),
                        rs.getInt("trigger")))
                .list();
        jdbc.sql("DELETE FROM scheduler.run_inflight WHERE team_id=:team AND target_id=:job AND state IN (1,2,4)")
                .param("team", team.value())
                .param("job", jobId)
                .update();
        for (PendingRun run : pending) {
            jdbc.sql(INSERT_CANCEL_HISTORY)
                    .param("scheduled", utc(run.scheduledAt()))
                    .param("run", run.runId())
                    .param("attempt", run.attempt())
                    .param("infra", run.infraAttempt())
                    .param("team", team.value())
                    .param("job", jobId)
                    .param("endpoint", run.endpointId())
                    .param("trigger", run.trigger())
                    .param("finished", utc(now))
                    .update();
        }
        for (CancelRun run : running) {
            jdbc.sql("UPDATE scheduler.run_inflight SET cancel_requested=true WHERE run_id=:run AND shard_id=:shard")
                    .param("run", run.runId())
                    .param("shard", shard)
                    .update();
            Cancel cancel = Cancel.newBuilder()
                    .setRunId(ProtoIds.toBytes(run.runId()))
                    .setShard(shard)
                    .setFence(run.fence())
                    .setReason(CancelReason.CANCEL_REASON_REQUESTED)
                    .setRequestedAt(Timestamp.newBuilder()
                            .setSeconds(now.getEpochSecond())
                            .setNanos(now.getNano())
                            .build())
                    .build();
            outbox.write(OutboxMessage.cancel(cancel, now, null));
        }
    }

    private RunTarget target(
            TeamId team, UUID jobId, int version, JobEndpointCatalog.Endpoint endpoint, JobSpec spec, CallSpec call) {
        java.time.Duration lateness =
                spec.maxLatenessSec() == null ? null : java.time.Duration.ofSeconds(spec.maxLatenessSec());
        return new RunTarget(
                team,
                TargetType.JOB,
                jobId,
                version,
                jobId,
                null,
                new EndpointId(endpoint.id()),
                Math.toIntExact(endpoint.version()),
                Priority.of(spec.priority()),
                lateness,
                call);
    }

    private static InflightRun withDueAt(InflightRun run, Instant dueAt) {
        return new InflightRun(
                run.runId(),
                run.teamId(),
                run.targetType(),
                run.definitionId(),
                run.definitionVersion(),
                run.targetId(),
                run.taskKey(),
                run.endpointId(),
                run.endpointVersion(),
                run.scheduledAt(),
                dueAt,
                run.state(),
                run.attempt(),
                run.seriesStart(),
                run.seriesStartedAt(),
                run.infraAttempt(),
                run.fence(),
                run.remainingDeps(),
                run.priority(),
                run.trigger(),
                run.maxLatenessMs());
    }

    private LockedJob lock(JdbcClient jdbc, TeamId team, UUID id) {
        return jdbc.sql(LOCK)
                .param("team", team.value())
                .param("id", id)
                .query((rs, row) -> new LockedJob(rs.getInt("version"), rs.getString("status"), rs.getString("spec")))
                .optional()
                .orElseThrow(JdbcJobStore::notFound);
    }

    private static Job job(java.sql.ResultSet rs, JsonMapper json) throws java.sql.SQLException {
        String lastId = rs.getString("last_run_id");
        LastRun last = lastId == null
                ? null
                : new LastRun(
                        UUID.fromString(lastId), rs.getString("last_run_status"), instant(rs, "last_run_finished_at"));
        JsonNode spec = json.readTree(rs.getString("spec"));
        return new Job(
                rs.getObject("job_id", UUID.class),
                rs.getString("name"),
                rs.getString("status"),
                spec.path("endpoint").asString(),
                spec.path("priority").asInt(5),
                rs.getLong("version"),
                spec,
                instant(rs, "next_fire_at"),
                last,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String specJson(JobSpec spec) {
        return spec.serialized().toString();
    }

    private int priority(String spec) {
        return json.readTree(spec).path("priority").asInt(5);
    }

    private static boolean isNameConflict(org.springframework.dao.DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("jobs_team_name_uk")) return true;
            current = current.getCause();
        }
        return false;
    }

    private static Instant instant(java.sql.ResultSet rs, String field) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable Integer nullableInteger(java.sql.ResultSet rs, String field) throws java.sql.SQLException {
        int value = rs.getInt(field);
        return rs.wasNull() ? null : value;
    }

    private static NotFoundException notFound() {
        return new NotFoundException("The job does not exist.");
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record LockedJob(int version, String status, String spec) {}

    private record CancelRun(UUID runId, int fence) {}

    private record PendingRun(
            UUID runId, int attempt, int infraAttempt, Instant scheduledAt, UUID endpointId, int trigger) {}
}
