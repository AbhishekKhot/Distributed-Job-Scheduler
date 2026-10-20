package dev.kairos.engine.run;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.shard.Shard;
import dev.kairos.engine.outbox.CellTransactionGuard;
import dev.kairos.persistence.cell.Cell;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Inserts {@code run_inflight} rows so that each occurrence is created at most once.
 *
 * <p>Rows are written set-based: one multi-row {@code INSERT ... ON CONFLICT (shard_id, target_id, task_key,
 * scheduled_at) DO NOTHING RETURNING run_id} per chunk of {@value #CHUNK_SIZE} rows. The conflict target is the
 * occurrence constraint {@code run_inflight_occurrence_uk}; the migration declares it with {@code shard_id} first
 * because a unique constraint on a partitioned table must include the partition key, and the target id embeds the
 * shard, so it is the LLD's {@code (target_id, task_key, scheduled_at)}. A row whose occurrence already exists is
 * skipped and not returned, so a fire poller that retries after a failover, or two API requests racing, create one run.
 *
 * <p>The inserter must be called inside a {@link dev.kairos.persistence.tx.CellTransactions} callback on the rows'
 * cell, so the runs commit together with their outbox rows and whatever state change created them; it throws
 * {@link IllegalStateException} otherwise.
 *
 * <p>Thread-safe.
 */
public final class RunInserter {

    /** Rows per statement: 22 parameters each stay far below PostgreSQL's 65,535 bind parameters. */
    static final int CHUNK_SIZE = 500;

    private static final String COLUMNS = """
            INSERT INTO scheduler.run_inflight (shard_id, run_id, team_id, target_type, definition_id,
                definition_version, target_id, task_key, endpoint_id, endpoint_version, scheduled_at, due_at, state,
                attempt, series_start, series_started_at, infra_attempt, fence, remaining_deps, priority, trigger,
                max_lateness_ms)
            VALUES
            """;
    private static final String CONFLICT = """

            ON CONFLICT (shard_id, target_id, task_key, scheduled_at) DO NOTHING
            RETURNING run_id
            """;
    private static final List<String> PARAMETERS = List.of(
            "shardId",
            "runId",
            "teamId",
            "targetType",
            "definitionId",
            "definitionVersion",
            "targetId",
            "taskKey",
            "endpointId",
            "endpointVersion",
            "scheduledAt",
            "dueAt",
            "state",
            "attempt",
            "seriesStart",
            "seriesStartedAt",
            "infraAttempt",
            "fence",
            "remainingDeps",
            "priority",
            "trigger",
            "maxLatenessMs");

    private final CellTransactionGuard guard;

    /**
     * Creates the inserter.
     *
     * @param guard checks that a transaction is open on the rows' cell
     */
    public RunInserter(CellTransactionGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /**
     * Inserts the rows whose occurrence does not exist yet.
     *
     * @param runs the rows, all on the cell of the open transaction; may be empty
     * @return the ids of the rows that were inserted; rows of existing occurrences are missing
     * @throws IllegalStateException if a row's cell has no open transaction on this thread; nothing is written then
     */
    public Set<RunId> insert(List<InflightRun> runs) {
        Objects.requireNonNull(runs, "runs");
        if (runs.isEmpty()) {
            return Set.of();
        }
        Set<Shard> shards = new HashSet<>();
        for (InflightRun run : runs) {
            shards.add(Objects.requireNonNull(run, "run").shard());
        }
        Cell cell = guard.requireTransaction(runs.getFirst().shard());
        for (Shard shard : shards) {
            guard.requireTransaction(shard);
        }
        Set<RunId> inserted = new HashSet<>();
        for (int from = 0; from < runs.size(); from += CHUNK_SIZE) {
            List<InflightRun> chunk = runs.subList(from, Math.min(runs.size(), from + CHUNK_SIZE));
            MapSqlParameterSource parameters = new MapSqlParameterSource();
            for (int i = 0; i < chunk.size(); i++) {
                bind(parameters, i, chunk.get(i));
            }
            List<RunId> ids = cell.jdbc()
                    .sql(insertSql(chunk.size()))
                    .paramSource(parameters)
                    .query((resultSet, rowNumber) ->
                            new RunId(Objects.requireNonNull(resultSet.getObject(1, UUID.class), "run_id")))
                    .list();
            inserted.addAll(ids);
        }
        return Set.copyOf(inserted);
    }

    /**
     * Returns the statement for a number of rows. Only parameter names, numbered by row, vary; values are always bound.
     *
     * @param rows the number of rows, 1 to {@value #CHUNK_SIZE}
     * @return the statement
     */
    static String insertSql(int rows) {
        if (rows < 1 || rows > CHUNK_SIZE) {
            throw new IllegalArgumentException("A statement inserts 1 to " + CHUNK_SIZE + " rows");
        }
        StringBuilder sql = new StringBuilder(COLUMNS.length() + rows * 400 + CONFLICT.length()).append(COLUMNS);
        for (int row = 0; row < rows; row++) {
            sql.append(row == 0 ? "    (" : ",\n    (");
            for (int column = 0; column < PARAMETERS.size(); column++) {
                if (column > 0) {
                    sql.append(", ");
                }
                sql.append(':').append(PARAMETERS.get(column)).append(row);
            }
            sql.append(')');
        }
        return sql.append(CONFLICT).toString();
    }

    private static void bind(MapSqlParameterSource parameters, int row, InflightRun run) {
        // Explicit SQL types, so null lateness limits bind without a metadata round trip and small codes go as
        // smallint.
        parameters
                .addValue("shardId" + row, smallint(run.shard().value()), Types.SMALLINT)
                .addValue("runId" + row, run.runId().value(), Types.OTHER)
                .addValue("teamId" + row, run.teamId().value(), Types.OTHER)
                .addValue("targetType" + row, smallint(run.targetType().code()), Types.SMALLINT)
                .addValue("definitionId" + row, run.definitionId(), Types.OTHER)
                .addValue("definitionVersion" + row, run.definitionVersion(), Types.INTEGER)
                .addValue("targetId" + row, run.targetId(), Types.OTHER)
                .addValue("taskKey" + row, run.taskKeyColumn(), Types.VARCHAR)
                .addValue("endpointId" + row, run.endpointId().value(), Types.OTHER)
                .addValue("endpointVersion" + row, run.endpointVersion(), Types.INTEGER)
                .addValue("scheduledAt" + row, utc(run.scheduledAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("dueAt" + row, utc(run.dueAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("state" + row, smallint(run.state().code()), Types.SMALLINT)
                .addValue("attempt" + row, smallint(run.attempt()), Types.SMALLINT)
                .addValue("seriesStart" + row, smallint(run.seriesStart()), Types.SMALLINT)
                .addValue("seriesStartedAt" + row, utc(run.seriesStartedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("infraAttempt" + row, smallint(run.infraAttempt()), Types.SMALLINT)
                .addValue("fence" + row, run.fence().value(), Types.INTEGER)
                .addValue("remainingDeps" + row, smallint(run.remainingDeps()), Types.SMALLINT)
                .addValue("priority" + row, smallint(run.priority().value()), Types.SMALLINT)
                .addValue("trigger" + row, smallint(run.trigger().code()), Types.SMALLINT)
                .addValue("maxLatenessMs" + row, run.maxLatenessMs(), Types.INTEGER);
    }

    private static short smallint(int value) {
        // Every caller passes a value validated by InflightRun to fit a smallint column.
        return (short) value;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
