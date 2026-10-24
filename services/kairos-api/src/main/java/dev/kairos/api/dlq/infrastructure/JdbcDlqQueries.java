package dev.kairos.api.dlq.infrastructure;

import dev.kairos.api.dlq.application.DlqQueries;
import dev.kairos.api.dlq.application.DlqQueries.Entry;
import dev.kairos.api.dlq.application.DlqQueries.Filter;
import dev.kairos.api.dlq.application.DlqQueries.Key;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.DlqStatus;
import dev.kairos.domain.model.ErrorClass;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.run.Fence;
import dev.kairos.engine.run.CallSpec;
import dev.kairos.engine.run.RunCreator;
import dev.kairos.engine.run.RunFactory;
import dev.kairos.engine.run.RunTarget;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.proto.v1.NameValue;
import dev.kairos.proto.v1.RequestSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JDBC reads and transactional triage mutations for dead letters. */
public final class JdbcDlqQueries implements DlqQueries {
    private static final String ENDPOINT_ID = """
            SELECT endpoint_id FROM scheduler.api_endpoints WHERE team_id = :team AND name = :name
            """;
    private static final String ENDPOINT_NAMES = """
            SELECT endpoint_id, name FROM scheduler.api_endpoints WHERE team_id = :team AND endpoint_id IN (:ids)
            """;
    private static final String COLUMNS = """
            SELECT dlq_id, team_id, run_id, shard_id, scheduled_at, target_type, definition_id, definition_version,
                   target_id, task_key, endpoint_id, endpoint_version, priority, fence,
                   reason, last_http_status, last_error, attempt, status, assignee, notes, created_at, resolved_at
              FROM scheduler.dead_letters WHERE team_id = :team
            """;
    private static final String LOCK = """
            SELECT dlq_id, team_id, run_id, shard_id, scheduled_at, target_type, definition_id, definition_version,
                   target_id, task_key, endpoint_id, endpoint_version, priority, fence,
                   reason, last_http_status, last_error, attempt, status, assignee, notes, created_at, resolved_at
              FROM scheduler.dead_letters WHERE team_id = :team AND dlq_id = :id FOR UPDATE
            """;
    private static final String RELOAD = """
            SELECT dlq_id, team_id, run_id, shard_id, scheduled_at, target_type, definition_id, definition_version,
                   target_id, task_key, endpoint_id, endpoint_version, priority, fence,
                   reason, last_http_status, last_error, attempt, status, assignee, notes, created_at, resolved_at
              FROM scheduler.dead_letters WHERE team_id = :team AND dlq_id = :id
            """;
    private static final String ACK = """
            UPDATE scheduler.dead_letters SET status = 2, assignee = :assignee, notes = :notes
             WHERE team_id = :team AND dlq_id = :id AND status IN (1, 2)
            """;
    private static final String DISCARD = """
            UPDATE scheduler.dead_letters SET status = 4, notes = :reason, resolved_by = :actor, resolved_at = :now
             WHERE team_id = :team AND dlq_id = :id AND status IN (1, 2)
            """;
    private static final String REDRIVEN = """
            UPDATE scheduler.dead_letters SET status = 3, redrive_mode = :mode, resolved_by = :actor, resolved_at = :now
             WHERE team_id = :team AND dlq_id = :id AND status IN (1, 2)
            """;

    private final JdbcClient control;
    private final CellRouter cells;
    private final Clock clock;
    private final RunFactory factory;
    private final RunCreator creator;
    private final JsonMapper json;

    /** Creates the adapter. */
    public JdbcDlqQueries(
            JdbcClient control,
            CellRouter cells,
            Clock clock,
            RunFactory factory,
            RunCreator creator,
            JsonMapper json) {
        this.control = Objects.requireNonNull(control, "control");
        this.cells = Objects.requireNonNull(cells, "cells");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.creator = Objects.requireNonNull(creator, "creator");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public List<Entry> list(TeamId team, Filter filter, @Nullable Key after, int limit) {
        UUID endpointId = resolveEndpointId(team, filter.endpoint());
        if (filter.endpoint() != null && endpointId == null) return List.of();
        List<DlqRow> rows = cells.allCells().parallelStream()
                .map(cell -> listCell(cell, team, filter, endpointId, after, limit))
                .flatMap(List::stream)
                .sorted(Comparator.comparing(DlqRow::createdAt)
                        .thenComparing(DlqRow::dlqId)
                        .reversed())
                .limit(limit)
                .toList();
        return entries(team, rows);
    }

    private List<DlqRow> listCell(
            Cell cell, TeamId team, Filter filter, @Nullable UUID endpointId, @Nullable Key after, int limit) {
        StringBuilder sql = new StringBuilder(COLUMNS);
        if (filter.status() != null) sql.append(" AND status = :status");
        if (filter.reason() != null) sql.append(" AND reason = :reason");
        if (endpointId != null) sql.append(" AND endpoint_id = :endpoint");
        if (after != null) sql.append(" AND (created_at, dlq_id) < (:afterAt, :afterId)");
        sql.append(" ORDER BY created_at DESC, dlq_id DESC LIMIT :limit");
        JdbcClient.StatementSpec query =
                cell.jdbc().sql(sql.toString()).param("team", team.value()).param("limit", limit);
        if (filter.status() != null)
            query = query.param("status", filter.status().code());
        if (filter.reason() != null)
            query = query.param("reason", filter.reason().code());
        if (endpointId != null) query = query.param("endpoint", endpointId);
        if (after != null)
            query = query.param("afterAt", after.createdAt().atOffset(ZoneOffset.UTC))
                    .param("afterId", after.dlqId());
        return query.query((rs, row) -> row(rs)).list();
    }

    @Override
    public Optional<Entry> get(TeamId team, UUID dlqId) {
        Cell cell = cells.forId(dlqId);
        return cell.jdbc()
                .sql(COLUMNS + " AND dlq_id = :id")
                .param("team", team.value())
                .param("id", dlqId)
                .query((rs, row) -> row(rs))
                .optional()
                .map(value -> entries(team, List.of(value)).getFirst());
    }

    @Override
    public Optional<Entry> acknowledge(TeamId team, UUID dlqId, String assignee, @Nullable String notes) {
        return mutate(team, dlqId, (cell, row) -> {
            requireUnresolved(row);
            int changed = cell.jdbc()
                    .sql(ACK)
                    .param("team", team.value())
                    .param("id", dlqId)
                    .param("assignee", assignee)
                    .param("notes", notes)
                    .update();
            if (changed != 1) throw new IllegalStateException("Locked DLQ entry was not acknowledged");
        });
    }

    @Override
    public Optional<Entry> discard(TeamId team, UUID dlqId, String actor, String reason) {
        return mutate(team, dlqId, (cell, row) -> {
            requireUnresolved(row);
            int changed = cell.jdbc()
                    .sql(DISCARD)
                    .param("team", team.value())
                    .param("id", dlqId)
                    .param("reason", reason)
                    .param("actor", actor)
                    .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                    .update();
            if (changed != 1) throw new IllegalStateException("Locked DLQ entry was not discarded");
        });
    }

    @Override
    public Optional<Entry> redrive(TeamId team, UUID dlqId, String mode, String actor) {
        Cell cell = cells.forId(dlqId);
        Optional<DlqRow> row = cell.transactions().execute(jdbc -> {
            Optional<DlqRow> selected = jdbc.sql(LOCK)
                    .param("team", team.value())
                    .param("id", dlqId)
                    .query((rs, index) -> row(rs))
                    .optional();
            if (selected.isEmpty()) return Optional.empty();
            DlqRow value = selected.orElseThrow();
            requireUnresolved(value);
            TargetConfig target = target(team, cell, value, mode);
            var planned = factory.redrive(
                    target.target(),
                    new RunId(value.runId()),
                    value.scheduledAt(),
                    value.attempt(),
                    new Fence(value.fence()));
            creator.create(planned, null);
            if (value.targetType() == 2) {
                jdbc.sql(
                                "UPDATE scheduler.workflow_runs SET status=1, blocked_until=NULL WHERE workflow_run_id=:id AND status=2")
                        .param("id", value.targetId())
                        .update();
            }
            jdbc.sql(REDRIVEN)
                    .param("mode", "ORIGINAL".equals(mode) ? 1 : 2)
                    .param("actor", actor)
                    .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                    .param("team", team.value())
                    .param("id", dlqId)
                    .update();
            return jdbc.sql(RELOAD)
                    .param("team", team.value())
                    .param("id", dlqId)
                    .query((rs, index) -> row(rs))
                    .optional();
        });
        return row.map(value -> entries(team, List.of(value)).getFirst());
    }

    @Override
    public List<UUID> bulkCandidates(TeamId team, DlqQueries.BulkFilter filter) {
        UUID endpoint = resolveEndpointId(team, filter.endpoint());
        if (filter.endpoint() != null && endpoint == null) return List.of();
        return cells.allCells().parallelStream()
                .map(cell -> {
                    StringBuilder sql = new StringBuilder(
                            "SELECT dlq_id FROM scheduler.dead_letters WHERE team_id=:team AND status IN (1,2)");
                    if (endpoint != null) sql.append(" AND endpoint_id=:endpoint");
                    if (filter.reason() != null) sql.append(" AND reason=:reason");
                    if (filter.from() != null) sql.append(" AND created_at>=:from");
                    if (filter.to() != null) sql.append(" AND created_at<=:to");
                    JdbcClient.StatementSpec query =
                            cell.jdbc().sql(sql.toString()).param("team", team.value());
                    if (endpoint != null) query = query.param("endpoint", endpoint);
                    if (filter.reason() != null)
                        query = query.param("reason", filter.reason().code());
                    if (filter.from() != null)
                        query = query.param("from", filter.from().atOffset(ZoneOffset.UTC));
                    if (filter.to() != null)
                        query = query.param("to", filter.to().atOffset(ZoneOffset.UTC));
                    return query.query(UUID.class).list();
                })
                .flatMap(List::stream)
                .toList();
    }

    private TargetConfig target(TeamId team, Cell cell, DlqRow row, String mode) {
        boolean current = "CURRENT".equals(mode);
        String endpointSql = current
                ? "SELECT endpoint_id, name, status, version, spec::text AS spec FROM scheduler.api_endpoints WHERE team_id=:team AND endpoint_id=:id"
                : "SELECT e.endpoint_id, e.name, e.status, v.version, v.spec::text AS spec FROM scheduler.api_endpoints e JOIN scheduler.api_endpoint_versions v ON v.endpoint_id=e.endpoint_id WHERE e.team_id=:team AND e.endpoint_id=:id AND v.version=:version";
        JdbcClient.StatementSpec endpointQuery =
                control.sql(endpointSql).param("team", team.value()).param("id", row.endpointId());
        if (!current) endpointQuery = endpointQuery.param("version", row.endpointVersion());
        EndpointConfig endpoint = endpointQuery
                .query((rs, index) -> new EndpointConfig(
                        rs.getObject("endpoint_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getInt("version"),
                        parseJson(rs.getString("spec"))))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Pinned endpoint configuration is missing"));
        if (!"ACTIVE".equals(endpoint.status()))
            throw new ConflictException(
                    ErrorCode.ENDPOINT_SUSPENDED, "Resume endpoint " + endpoint.name() + " before redriving.");
        int type = row.targetType();
        UUID definitionId = row.definitionId();
        int definitionVersion = row.definitionVersion();
        JsonNode definition;
        String definitionSql;
        if (type == 1) {
            definitionSql = current
                    ? "SELECT version, spec::text AS spec FROM scheduler.jobs WHERE team_id=:team AND job_id=:id"
                    : "SELECT version, spec::text AS spec FROM scheduler.job_versions WHERE job_id=:id AND version=:version";
        } else {
            definitionSql = current
                    ? "SELECT version, spec::text AS spec FROM scheduler.workflows WHERE team_id=:team AND workflow_id=:id"
                    : "SELECT version, spec::text AS spec FROM scheduler.workflow_versions WHERE workflow_id=:id AND version=:version";
        }
        JdbcClient.StatementSpec definitionQuery =
                cell.jdbc().sql(definitionSql).param("id", definitionId);
        if (current) definitionQuery = definitionQuery.param("team", team.value());
        else definitionQuery = definitionQuery.param("version", definitionVersion);
        DefinitionConfig definitionRow = definitionQuery
                .query((rs, index) -> new DefinitionConfig(rs.getInt("version"), parseJson(rs.getString("spec"))))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Pinned definition is missing"));
        if (current) definitionVersion = definitionRow.version();
        JsonNode request;
        if (type == 1) {
            request = definitionRow.spec().path("request");
            if (current)
                endpoint = endpointForName(
                        team, definitionRow.spec().path("endpoint").asString());
        } else {
            JsonNode task = definitionRow.spec().path("tasks").path(row.taskKey());
            request = task.path("request");
            if (current) endpoint = endpointForName(team, task.path("endpoint").asString());
        }
        if (!"ACTIVE".equals(endpoint.status()))
            throw new ConflictException(
                    ErrorCode.ENDPOINT_SUSPENDED, "Resume endpoint " + endpoint.name() + " before redriving.");
        JsonNode lateness = type == 1
                ? definitionRow.spec().path("maxLatenessSec")
                : definitionRow.spec().path("tasks").path(row.taskKey()).path("maxLatenessSec");
        java.time.Duration maxLateness =
                lateness.isNumber() ? java.time.Duration.ofSeconds(lateness.longValue()) : null;
        return new TargetConfig(new RunTarget(
                team,
                TargetType.fromCode(type),
                definitionId,
                definitionVersion,
                row.targetId(),
                type == 2 ? new TaskKey(row.taskKey()) : null,
                new EndpointId(endpoint.id()),
                endpoint.version(),
                new Priority(row.priority()),
                maxLateness,
                new CallSpec(endpoint.spec().path("timeoutMs").asInt(30_000), snapshot(request, endpoint.spec()))));
    }

    private EndpointConfig endpointForName(TeamId team, String name) {
        return control.sql(
                        "SELECT endpoint_id, name, status, version, spec::text AS spec FROM scheduler.api_endpoints WHERE team_id=:team AND name=:name")
                .param("team", team.value())
                .param("name", name)
                .query((rs, index) -> new EndpointConfig(
                        rs.getObject("endpoint_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getInt("version"),
                        parseJson(rs.getString("spec"))))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Current endpoint configuration is missing"));
    }

    private JsonNode parseJson(String text) {
        try {
            return json.readTree(text);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("Stored configuration is invalid", e);
        }
    }

    private RequestSnapshot snapshot(JsonNode request, JsonNode endpoint) {
        RequestSnapshot.Builder result = RequestSnapshot.newBuilder();
        JsonNode paths = request.path("pathParams");
        if (paths.isObject())
            paths.properties()
                    .forEach(entry -> result.putPathParams(
                            entry.getKey(), entry.getValue().asString()));
        JsonNode query = request.path("query");
        if (query.isObject())
            query.properties()
                    .forEach(entry -> result.addQuery(NameValue.newBuilder()
                            .setName(entry.getKey())
                            .setValue(entry.getValue().asString())
                            .build()));
        Map<String, NameValue> headers = new LinkedHashMap<>();
        mergeHeaders(headers, endpoint.path("defaultHeaders"));
        mergeHeaders(headers, request.path("headers"));
        result.addAllHeaders(headers.values());
        JsonNode body = request.path("body");
        if (!body.isMissingNode() && !body.isNull())
            result.setBody(com.google.protobuf.ByteString.copyFrom(json.writeValueAsBytes(body)));
        JsonNode secret = endpoint.path("auth").path("secretRef");
        if (secret.isString()) result.addSecretRefs(secret.asString());
        return result.build();
    }

    private static void mergeHeaders(Map<String, NameValue> headers, JsonNode values) {
        if (!values.isObject()) return;
        values.properties().forEach(entry -> {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (!Set.of("authorization", "proxy-authorization", "cookie", "set-cookie")
                    .contains(key))
                headers.put(
                        key,
                        NameValue.newBuilder()
                                .setName(key)
                                .setValue(entry.getValue().asString())
                                .build());
        });
    }

    private record DefinitionConfig(int version, JsonNode spec) {}

    private record EndpointConfig(UUID id, String name, String status, int version, JsonNode spec) {}

    private record TargetConfig(RunTarget target) {}

    private Optional<Entry> mutate(TeamId team, UUID dlqId, Mutation mutation) {
        Cell cell = cells.forId(dlqId);
        Optional<DlqRow> row = cell.transactions().execute(jdbc -> {
            Optional<DlqRow> selected = jdbc.sql(LOCK)
                    .param("team", team.value())
                    .param("id", dlqId)
                    .query((rs, index) -> row(rs))
                    .optional();
            if (selected.isEmpty()) return Optional.empty();
            DlqRow value = selected.orElseThrow();
            mutation.apply(cell, value);
            return jdbc.sql(RELOAD)
                    .param("team", team.value())
                    .param("id", dlqId)
                    .query((rs, index) -> row(rs))
                    .optional();
        });
        return row.map(value -> entries(team, List.of(value)).getFirst());
    }

    private List<Entry> entries(TeamId team, List<DlqRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<UUID> ids = rows.stream().map(DlqRow::endpointId).distinct().toList();
        Map<UUID, String> names = control
                .sql(ENDPOINT_NAMES)
                .param("team", team.value())
                .param("ids", ids)
                .query((rs, index) -> Map.entry(rs.getObject("endpoint_id", UUID.class), rs.getString("name")))
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        return rows.stream()
                .map(row -> new Entry(
                        row.dlqId(),
                        row.runId(),
                        row.targetType() == 1 ? row.targetId() : null,
                        row.targetType() == 2 ? row.targetId() : null,
                        row.taskKey().isEmpty() ? null : row.taskKey(),
                        names.getOrDefault(row.endpointId(), "unknown"),
                        ErrorClass.fromCode(row.reason()),
                        row.lastHttpStatus(),
                        row.lastError(),
                        row.attempt(),
                        DlqStatus.fromCode(row.status()),
                        row.assignee(),
                        row.notes(),
                        row.createdAt(),
                        row.resolvedAt()))
                .toList();
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

    private static void requireUnresolved(DlqRow row) {
        DlqStatus status = DlqStatus.fromCode(row.status());
        if (status.isResolved()) {
            throw new ConflictException(ErrorCode.DLQ_RESOLVED, "The entry was already redriven or discarded.");
        }
    }

    private static DlqRow row(ResultSet rs) throws SQLException {
        return new DlqRow(
                rs.getObject("dlq_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                instant(rs, "scheduled_at"),
                rs.getInt("target_type"),
                rs.getObject("definition_id", UUID.class),
                rs.getInt("definition_version"),
                rs.getObject("target_id", UUID.class),
                rs.getString("task_key"),
                rs.getObject("endpoint_id", UUID.class),
                rs.getInt("endpoint_version"),
                rs.getInt("priority"),
                rs.getInt("fence"),
                rs.getInt("reason"),
                nullableInt(rs, "last_http_status"),
                rs.getString("last_error"),
                rs.getInt("attempt"),
                rs.getInt("status"),
                rs.getString("assignee"),
                rs.getString("notes"),
                instant(rs, "created_at"),
                nullableInstant(rs, "resolved_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    @FunctionalInterface
    private interface Mutation {
        void apply(Cell cell, DlqRow row);
    }

    private record DlqRow(
            UUID dlqId,
            UUID runId,
            Instant scheduledAt,
            int targetType,
            UUID definitionId,
            int definitionVersion,
            UUID targetId,
            String taskKey,
            UUID endpointId,
            int endpointVersion,
            int priority,
            int fence,
            int reason,
            @Nullable Integer lastHttpStatus,
            @Nullable String lastError,
            int attempt,
            int status,
            @Nullable String assignee,
            @Nullable String notes,
            Instant createdAt,
            @Nullable Instant resolvedAt) {}
}
