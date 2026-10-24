package dev.kairos.api.runs.infrastructure;

import com.google.protobuf.Timestamp;
import dev.kairos.api.runs.application.RunQueries;
import dev.kairos.api.runs.application.RunQueries.Attempt;
import dev.kairos.api.runs.application.RunQueries.Filter;
import dev.kairos.api.runs.application.RunQueries.Key;
import dev.kairos.api.runs.application.RunQueries.RunDetails;
import dev.kairos.api.runs.application.RunQueries.RunSummary;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.ErrorClass;
import dev.kairos.domain.model.Outcome;
import dev.kairos.domain.model.RunState;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.run.RunStatus;
import dev.kairos.engine.outbox.OutboxMessage;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Cancel;
import dev.kairos.proto.v1.CancelReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads team-scoped runs from the cell databases and endpoint names from the control database. */
public final class JdbcRunQueries implements RunQueries {
    private static final String CURRENT_RUNS = """
            WITH final_history AS (
                SELECT DISTINCT ON (run_id) run_id, target_type, target_id, definition_id, endpoint_id, task_key,
                       attempt, scheduled_at, finished_at, http_status, latency_ms, error_class,
                       outcome, trigger, team_id, started_at, error
                  FROM scheduler.run_history
                 WHERE team_id = :team AND final
                 ORDER BY run_id, attempt DESC, infra_attempt DESC
            ), current_runs AS (
                SELECT i.run_id, i.team_id, i.target_type,
                       CASE WHEN i.target_type = 1 THEN i.target_id END AS job_id,
                       CASE WHEN i.target_type = 2 THEN i.target_id END AS workflow_run_id,
                       nullif(i.task_key, '') AS task_key,
                       CASE WHEN i.cancel_requested THEN 'CANCELLED'
                            ELSE CASE i.state WHEN 1 THEN 'WAITING' WHEN 2 THEN 'QUEUED'
                                 WHEN 3 THEN 'RUNNING' WHEN 4 THEN 'DELAYED' END END AS status,
                       i.attempt, i.scheduled_at, h.finished_at, h.http_status, h.latency_ms,
                       i.trigger, i.definition_id, i.endpoint_id, h.started_at, h.error_class, h.error
                  FROM scheduler.run_inflight i
                  LEFT JOIN LATERAL (
                      SELECT started_at, finished_at, http_status, latency_ms, error_class, error
                        FROM scheduler.run_history h
                       WHERE h.run_id = i.run_id
                       ORDER BY h.attempt DESC, h.infra_attempt DESC LIMIT 1
                  ) h ON true
                 WHERE i.team_id = :team
                UNION ALL
                SELECT h.run_id, h.team_id, h.target_type,
                       CASE WHEN h.target_type = 1 THEN h.target_id END AS job_id,
                       CASE WHEN h.target_type = 2 THEN h.target_id END AS workflow_run_id,
                       nullif(h.task_key, '') AS task_key,
                       CASE h.outcome WHEN 1 THEN 'SUCCEEDED' WHEN 3 THEN 'DEAD_LETTERED'
                            WHEN 4 THEN 'CANCELLED' WHEN 5 THEN 'SKIPPED' WHEN 6 THEN 'UPSTREAM_FAILED' END AS status,
                       h.attempt, h.scheduled_at, h.finished_at, h.http_status, h.latency_ms,
                       h.trigger, h.definition_id, h.endpoint_id, h.started_at, h.error_class, h.error
                  FROM final_history h
            )
            SELECT run_id, job_id, workflow_run_id, task_key, status, attempt, scheduled_at,
                   finished_at, http_status, latency_ms, trigger, started_at, error_class, error
              FROM current_runs
             WHERE team_id = :team
            """;

    private static final String ENDPOINT_ID = """
            SELECT endpoint_id FROM scheduler.api_endpoints WHERE team_id = :team AND name = :name
            """;

    private static final String INFLIGHT_GET = """
            SELECT i.run_id, i.target_id, i.target_type, i.definition_id, nullif(i.task_key, '') AS task_key,
                   i.state, i.attempt, i.scheduled_at, i.trigger, i.cancel_requested, h.started_at, h.finished_at,
                   h.http_status, h.latency_ms, h.error_class, h.error
              FROM scheduler.run_inflight i
              LEFT JOIN LATERAL (SELECT started_at, finished_at, http_status, latency_ms, error_class, error
                                   FROM scheduler.run_history h WHERE h.run_id = i.run_id
                                  ORDER BY h.attempt DESC, h.infra_attempt DESC LIMIT 1) h ON true
             WHERE i.team_id = :team AND i.run_id = :run
            """;

    private static final String FINAL_GET = """
            SELECT DISTINCT ON (h.run_id) h.run_id, h.target_id, h.target_type, h.definition_id,
                   nullif(h.task_key, '') AS task_key, h.attempt, h.scheduled_at, h.trigger,
                   false AS cancel_requested, h.started_at, h.finished_at, h.http_status, h.latency_ms,
                   h.error_class, h.error, h.outcome
              FROM scheduler.run_history h
             WHERE h.team_id = :team AND h.run_id = :run AND h.final
             ORDER BY h.run_id, h.attempt DESC, h.infra_attempt DESC
            """;

    private static final String ATTEMPTS = """
            SELECT attempt, outcome, http_status, latency_ms, finished_at
              FROM scheduler.run_history
             WHERE team_id = :team AND run_id = :run
             ORDER BY attempt, infra_attempt
            """;

    private static final String CANCEL_LOCK = """
            SELECT run_id, team_id, target_type, definition_id, target_id, task_key, endpoint_id,
                   attempt, infra_attempt, state, scheduled_at, trigger, fence, cancel_requested
              FROM scheduler.run_inflight WHERE team_id = :team AND run_id = :run FOR UPDATE
            """;
    private static final String CANCEL_DISPATCH_OUTBOX = """
            DELETE FROM scheduler.outbox WHERE shard_id = :shard AND run_id = :run AND topic IN (1, 2, 3)
            """;
    private static final String DELETE_PENDING = """
            DELETE FROM scheduler.run_inflight WHERE shard_id = :shard AND run_id = :run
            """;
    private static final String CANCEL_RUNNING = """
            UPDATE scheduler.run_inflight SET cancel_requested = true
             WHERE shard_id = :shard AND run_id = :run AND state = 3 AND NOT cancel_requested
            """;
    private static final String CANCEL_HISTORY = """
            INSERT INTO scheduler.run_history (
                scheduled_at, run_id, attempt, infra_attempt, team_id, target_type, definition_id,
                target_id, task_key, endpoint_id, trigger, outcome, error_class, final, finished_at)
            VALUES (:scheduled, :run, :attempt, :infra, :team, :type, :definition,
                    :target, :task, :endpoint, :trigger, 4, NULL, true, :finished)
            """;
    private static final String FINAL_STATUS = """
            SELECT outcome FROM scheduler.run_history WHERE team_id = :team AND run_id = :run AND final
             ORDER BY attempt DESC, infra_attempt DESC LIMIT 1
            """;

    private final JdbcClient control;
    private final CellRouter cells;
    private final OutboxWriter outbox;
    private final Clock clock;

    /** Creates the cell query adapter. */
    public JdbcRunQueries(JdbcClient control, CellRouter cells, OutboxWriter outbox, Clock clock) {
        this.control = Objects.requireNonNull(control, "control");
        this.cells = Objects.requireNonNull(cells, "cells");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<RunSummary> list(TeamId team, Filter filter, @Nullable Key after, int limit) {
        UUID endpointId = resolveEndpointId(team, filter.endpoint());
        if (filter.endpoint() != null && endpointId == null) return List.of();
        List<RunSummary> all = cells.allCells().parallelStream()
                .map(cell -> listCell(cell, team, filter, endpointId, after, limit))
                .flatMap(List::stream)
                .sorted(Comparator.comparing(RunSummary::scheduledAt)
                        .thenComparing(RunSummary::runId)
                        .reversed())
                .limit(limit)
                .toList();
        return List.copyOf(all);
    }

    private @Nullable UUID resolveEndpointId(TeamId team, @Nullable String endpoint) {
        if (endpoint == null) return null;
        return control.sql(ENDPOINT_ID)
                .param("team", team.value())
                .param("name", endpoint)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private List<RunSummary> listCell(
            Cell cell, TeamId team, Filter filter, @Nullable UUID endpointId, @Nullable Key after, int limit) {
        StringBuilder sql = new StringBuilder(CURRENT_RUNS);
        if (filter.status() != null) sql.append(" AND status = :status");
        if (filter.jobId() != null) sql.append(" AND job_id = :job");
        if (filter.from() != null) sql.append(" AND scheduled_at >= :from");
        if (filter.to() != null) sql.append(" AND scheduled_at <= :to");
        if (endpointId != null) sql.append(" AND endpoint_id = :endpoint");
        if (after != null) sql.append(" AND (scheduled_at, run_id) < (:afterAt, :afterId)");
        sql.append(" ORDER BY scheduled_at DESC, run_id DESC LIMIT :limit");
        JdbcClient.StatementSpec query =
                cell.jdbc().sql(sql.toString()).param("team", team.value()).param("limit", limit);
        if (filter.status() != null)
            query = query.param("status", filter.status().name());
        if (filter.jobId() != null) query = query.param("job", filter.jobId());
        if (filter.from() != null) query = query.param("from", filter.from().atOffset(ZoneOffset.UTC));
        if (filter.to() != null) query = query.param("to", filter.to().atOffset(ZoneOffset.UTC));
        if (endpointId != null) query = query.param("endpoint", endpointId);
        if (after != null)
            query = query.param("afterAt", after.scheduledAt().atOffset(ZoneOffset.UTC))
                    .param("afterId", after.runId());
        return query.query((rs, row) -> summary(rs)).list();
    }

    @Override
    public Optional<RunDetails> get(TeamId team, UUID runId) {
        Cell cell = cells.forId(runId);
        Optional<RawRun> raw = cell.jdbc()
                .sql(INFLIGHT_GET)
                .param("team", team.value())
                .param("run", runId)
                .query((rs, row) -> raw(rs, true))
                .optional();
        if (raw.isEmpty()) {
            raw = cell.jdbc()
                    .sql(FINAL_GET)
                    .param("team", team.value())
                    .param("run", runId)
                    .query((rs, row) -> raw(rs, false))
                    .optional();
        }
        if (raw.isEmpty()) return Optional.empty();
        RawRun run = raw.orElseThrow();
        List<Attempt> attempts = cell.jdbc()
                .sql(ATTEMPTS)
                .param("team", team.value())
                .param("run", runId)
                .query((rs, row) -> new Attempt(
                        rs.getInt("attempt"),
                        Outcome.fromCode(rs.getInt("outcome")).name(),
                        nullableInt(rs, "http_status"),
                        nullableInt(rs, "latency_ms"),
                        instant(rs, "finished_at")))
                .list();
        return Optional.of(new RunDetails(
                run.summary(),
                Trigger.fromCode(run.trigger()).name(),
                run.startedAt(),
                run.errorClass() == null
                        ? null
                        : ErrorClass.fromCode(run.errorClass()).name(),
                run.error(),
                attempts));
    }

    @Override
    public Optional<RunSummary> cancel(TeamId team, UUID runId) {
        Cell cell = cells.forId(runId);
        int shard = KairosId.shardOf(runId).value();
        return cell.transactions().execute(jdbc -> {
            Optional<CancelRun> selected = jdbc.sql(CANCEL_LOCK)
                    .param("team", team.value())
                    .param("run", runId)
                    .query((rs, row) -> new CancelRun(
                            rs.getObject("run_id", UUID.class),
                            rs.getObject("team_id", UUID.class),
                            rs.getInt("target_type"),
                            rs.getObject("definition_id", UUID.class),
                            rs.getObject("target_id", UUID.class),
                            rs.getString("task_key"),
                            rs.getObject("endpoint_id", UUID.class),
                            rs.getInt("attempt"),
                            rs.getInt("infra_attempt"),
                            rs.getInt("state"),
                            instant(rs, "scheduled_at"),
                            rs.getInt("trigger"),
                            rs.getInt("fence"),
                            rs.getBoolean("cancel_requested")))
                    .optional();
            if (selected.isEmpty()) {
                Integer outcome = jdbc.sql(FINAL_STATUS)
                        .param("team", team.value())
                        .param("run", runId)
                        .query(Integer.class)
                        .optional()
                        .orElse(null);
                if (outcome != null) {
                    RunStatus status = RunStatus.of(Outcome.fromCode(outcome));
                    throw new ConflictException(
                            ErrorCode.RUN_FINAL, "The run already finished with status " + status + ".");
                }
                return Optional.empty();
            }
            CancelRun run = selected.orElseThrow();
            Instant now = clock.instant();
            if (run.state() == 3) {
                if (!run.cancelRequested()) {
                    jdbc.sql(CANCEL_RUNNING)
                            .param("shard", shard)
                            .param("run", runId)
                            .update();
                    Cancel cancel = Cancel.newBuilder()
                            .setRunId(ProtoIds.toBytes(runId))
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
                return Optional.of(run.summary(RunStatus.CANCELLED, null));
            }
            jdbc.sql(CANCEL_DISPATCH_OUTBOX)
                    .param("shard", shard)
                    .param("run", runId)
                    .update();
            jdbc.sql(DELETE_PENDING).param("shard", shard).param("run", runId).update();
            jdbc.sql(CANCEL_HISTORY)
                    .param("scheduled", run.scheduledAt().atOffset(ZoneOffset.UTC))
                    .param("run", runId)
                    .param("attempt", run.attempt())
                    .param("infra", run.infraAttempt())
                    .param("team", run.teamId())
                    .param("type", run.targetType())
                    .param("definition", run.definitionId())
                    .param("target", run.targetId())
                    .param("task", run.taskKey())
                    .param("endpoint", run.endpointId())
                    .param("trigger", run.trigger())
                    .param("finished", now.atOffset(ZoneOffset.UTC))
                    .update();
            return Optional.of(run.summary(RunStatus.CANCELLED, now));
        });
    }

    private static RunSummary summary(ResultSet rs) throws SQLException {
        UUID target = rs.getObject("job_id", UUID.class);
        return new RunSummary(
                rs.getObject("run_id", UUID.class),
                target,
                rs.getObject("workflow_run_id", UUID.class),
                rs.getString("task_key"),
                RunStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt"),
                instant(rs, "scheduled_at"),
                nullableInstant(rs, "finished_at"),
                nullableInt(rs, "http_status"),
                nullableInt(rs, "latency_ms"));
    }

    private static RawRun raw(ResultSet rs, boolean inflight) throws SQLException {
        String taskKey = rs.getString("task_key");
        int outcome = inflight ? 0 : rs.getInt("outcome");
        RunStatus status = inflight
                ? (rs.getBoolean("cancel_requested")
                        ? RunStatus.CANCELLED
                        : RunStatus.of(RunState.fromCode(rs.getInt("state"))))
                : RunStatus.of(Outcome.fromCode(outcome));
        UUID targetId = rs.getObject("target_id", UUID.class);
        int targetType = rs.getInt("target_type");
        return new RawRun(
                rs.getObject("run_id", UUID.class),
                targetType == 1 ? targetId : null,
                targetType == 2 ? targetId : null,
                taskKey,
                status,
                rs.getInt("attempt"),
                instant(rs, "scheduled_at"),
                nullableInstant(rs, "finished_at"),
                nullableInt(rs, "http_status"),
                nullableInt(rs, "latency_ms"),
                rs.getInt("trigger"),
                nullableInstant(rs, "started_at"),
                nullableInt(rs, "error_class"),
                rs.getString("error"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime time = rs.getObject(column, OffsetDateTime.class);
        return time.toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime time = rs.getObject(column, OffsetDateTime.class);
        return time == null ? null : time.toInstant();
    }

    private static @Nullable Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private record RawRun(
            UUID runId,
            @Nullable UUID jobId,
            @Nullable UUID workflowRunId,
            @Nullable String taskKey,
            RunStatus status,
            int attempt,
            Instant scheduledAt,
            @Nullable Instant finishedAt,
            @Nullable Integer httpStatus,
            @Nullable Integer latencyMs,
            int trigger,
            @Nullable Instant startedAt,
            @Nullable Integer errorClass,
            @Nullable String error) {
        private RunSummary summary() {
            return new RunSummary(
                    runId,
                    jobId,
                    workflowRunId,
                    taskKey,
                    status,
                    attempt,
                    scheduledAt,
                    finishedAt,
                    httpStatus,
                    latencyMs);
        }
    }

    private record CancelRun(
            UUID runId,
            UUID teamId,
            int targetType,
            UUID definitionId,
            UUID targetId,
            String taskKey,
            UUID endpointId,
            int attempt,
            int infraAttempt,
            int state,
            Instant scheduledAt,
            int trigger,
            int fence,
            boolean cancelRequested) {
        private RunSummary summary(RunStatus status, @Nullable Instant finishedAt) {
            return new RunSummary(
                    runId,
                    targetType == 1 ? targetId : null,
                    targetType == 2 ? targetId : null,
                    taskKey.isEmpty() ? null : taskKey,
                    status,
                    attempt,
                    scheduledAt,
                    finishedAt,
                    null,
                    null);
        }
    }
}
