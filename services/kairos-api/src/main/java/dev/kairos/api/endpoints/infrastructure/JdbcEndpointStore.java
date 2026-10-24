package dev.kairos.api.endpoints.infrastructure;

import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.cabundles.application.CaBundleLookup;
import dev.kairos.api.endpoints.application.EndpointSpec;
import dev.kairos.api.endpoints.application.EndpointStore;
import dev.kairos.api.secrets.application.CredentialValue;
import dev.kairos.api.secrets.application.SecretLookup;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.NotFoundException;
import dev.kairos.domain.error.PreconditionFailedException;
import dev.kairos.domain.id.TeamId;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.tx.ControlTransactions;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** JDBC adapter for endpoint rows and immutable versions. */
public final class JdbcEndpointStore implements EndpointStore {
    private static final String SELECT = """
            SELECT endpoint_id, name, spec::text AS spec, status, status_reason, version, created_at, updated_at
              FROM scheduler.api_endpoints
            """;
    private final JdbcClient control;
    private final ControlTransactions transactions;
    private final AuditWriter audit;
    private final SecretLookup secrets;
    private final CaBundleLookup caBundles;
    private final JsonMapper json;
    private final CellRouter cells;

    public JdbcEndpointStore(
            JdbcClient control,
            ControlTransactions transactions,
            AuditWriter audit,
            SecretLookup secrets,
            CaBundleLookup caBundles,
            JsonMapper json,
            CellRouter cells) {
        this.control = control;
        this.transactions = transactions;
        this.audit = audit;
        this.secrets = secrets;
        this.caBundles = caBundles;
        this.json = json;
        this.cells = cells;
    }

    @Override
    public Endpoint find(TeamId team, UUID id) {
        return control.sql(SELECT + " WHERE team_id = :team AND endpoint_id = :id")
                .param("team", team.value())
                .param("id", id)
                .query(this::map)
                .optional()
                .orElseThrow(JdbcEndpointStore::notFound);
    }

    @Override
    public List<Endpoint> list(TeamId team, @Nullable String status, @Nullable Key after, int limit) {
        String sql = SELECT + " WHERE team_id = :team" + (status == null ? "" : " AND status = :status")
                + (after == null ? "" : " AND (created_at, endpoint_id) < (:after_at, :after_id)")
                + " ORDER BY created_at DESC, endpoint_id DESC LIMIT :limit";
        JdbcClient.StatementSpec query =
                control.sql(sql).param("team", team.value()).param("limit", limit);
        if (status != null) query = query.param("status", status);
        if (after != null) {
            query = query.param("after_at", after.createdAt().atOffset(ZoneOffset.UTC))
                    .param("after_id", after.id());
        }
        return query.query(this::map).list();
    }

    @Override
    public Endpoint create(TeamId team, String actor, UUID id, String name, EndpointSpec spec, Instant now) {
        return transactions.execute(jdbc -> {
            lock(team);
            if (control.sql("SELECT 1 FROM scheduler.api_endpoints WHERE team_id = :team AND name = :name")
                    .param("team", team.value())
                    .param("name", name)
                    .query(Integer.class)
                    .optional()
                    .isPresent()) {
                throw new ConflictException(
                        ErrorCode.ENDPOINT_NAME_TAKEN, "An endpoint with this name already exists.");
            }
            validateReferences(team, spec);
            String serialized = json.writeValueAsString(spec);
            checkSize(serialized);
            control.sql("""
                    INSERT INTO scheduler.api_endpoints
                        (endpoint_id, team_id, name, version, status, spec, created_at, updated_at)
                    VALUES (:id, :team, :name, 1, 'ACTIVE', CAST(:spec AS jsonb), :now, :now)
                    """)
                    .param("id", id)
                    .param("team", team.value())
                    .param("name", name)
                    .param("spec", serialized)
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .update();
            control.sql("""
                    INSERT INTO scheduler.api_endpoint_versions (endpoint_id, version, spec, created_at, created_by)
                    VALUES (:id, 1, CAST(:spec AS jsonb), :now, :actor)
                    """)
                    .param("id", id)
                    .param("spec", serialized)
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .param("actor", actor)
                    .update();
            record(team, actor, "ENDPOINT_CREATED", id, 1);
            return new Endpoint(id, name, spec, "ACTIVE", null, 1, now, now);
        });
    }

    @Override
    public Endpoint update(TeamId team, String actor, UUID id, long expectedVersion, EndpointSpec spec, Instant now) {
        return transactions.execute(jdbc -> {
            lock(team);
            Endpoint current = find(team, id);
            if (current.version() != expectedVersion) throw PreconditionFailedException.versionMismatch();
            validateReferences(team, spec);
            String serialized = json.writeValueAsString(spec);
            checkSize(serialized);
            int updated = control.sql("""
                    UPDATE scheduler.api_endpoints SET spec = CAST(:spec AS jsonb), version = version + 1,
                        updated_at = :now WHERE team_id = :team AND endpoint_id = :id AND version = :version
                    """)
                    .param("spec", serialized)
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .param("team", team.value())
                    .param("id", id)
                    .param("version", expectedVersion)
                    .update();
            if (updated != 1) throw PreconditionFailedException.versionMismatch();
            long version = expectedVersion + 1;
            control.sql("""
                    INSERT INTO scheduler.api_endpoint_versions (endpoint_id, version, spec, created_at, created_by)
                    VALUES (:id, :version, CAST(:spec AS jsonb), :now, :actor)
                    """)
                    .param("id", id)
                    .param("version", version)
                    .param("spec", serialized)
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .param("actor", actor)
                    .update();
            record(team, actor, "ENDPOINT_UPDATED", id, version);
            return new Endpoint(
                    id,
                    current.name(),
                    spec,
                    current.status(),
                    current.statusReason(),
                    version,
                    current.createdAt(),
                    now);
        });
    }

    @Override
    public void delete(TeamId team, String actor, UUID id) {
        long references = 0;
        for (var cell : cells.allCells()) {
            references += cell.jdbc()
                    .sql("""
                            SELECT count(*) FROM scheduler.jobs
                             WHERE team_id = :team AND endpoint_id = :id AND status <> 'DELETED'
                            """)
                    .param("team", team.value())
                    .param("id", id)
                    .query(Long.class)
                    .single();
        }
        if (references > 0) {
            throw new ConflictException(
                    ErrorCode.ENDPOINT_IN_USE,
                    references + " jobs still reference this endpoint. Delete them or disable the endpoint.");
        }
        transactions.execute(jdbc -> {
            lock(team);
            Endpoint current = find(team, id);
            control.sql("DELETE FROM scheduler.api_endpoint_versions WHERE endpoint_id = :id")
                    .param("id", id)
                    .update();
            control.sql("DELETE FROM scheduler.api_endpoints WHERE team_id = :team AND endpoint_id = :id")
                    .param("team", team.value())
                    .param("id", id)
                    .update();
            record(team, actor, "ENDPOINT_DELETED", id, current.version());
            return null;
        });
    }

    @Override
    public Endpoint transition(
            TeamId team, String actor, UUID id, String targetStatus, @Nullable String reason, Instant now) {
        return transactions.execute(jdbc -> {
            lock(team);
            Endpoint current = find(team, id);
            String expected = "SUSPENDED".equals(targetStatus) ? "ACTIVE" : "SUSPENDED";
            if (!expected.equals(current.status())) {
                throw new ConflictException(
                        ErrorCode.INVALID_STATE,
                        "SUSPENDED".equals(targetStatus)
                                ? "The endpoint is already suspended."
                                : "The endpoint is not suspended.");
            }
            if (reason != null && reason.length() > 64) {
                throw dev.kairos.domain.error.ValidationFailedException.of("reason", "length");
            }
            control.sql("""
                    UPDATE scheduler.api_endpoints
                       SET status = :status, status_reason = :reason, status_changed_at = :now, updated_at = :now
                     WHERE endpoint_id = :id AND team_id = :team AND status = :expected
                    """)
                    .param("status", targetStatus)
                    .param("reason", "SUSPENDED".equals(targetStatus) ? reason : null, java.sql.Types.VARCHAR)
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .param("id", id)
                    .param("team", team.value())
                    .param("expected", expected)
                    .update();
            record(
                    team,
                    actor,
                    "SUSPENDED".equals(targetStatus) ? "ENDPOINT_SUSPENDED" : "ENDPOINT_RESUMED",
                    id,
                    current.version());
            return new Endpoint(
                    id,
                    current.name(),
                    current.spec(),
                    targetStatus,
                    "SUSPENDED".equals(targetStatus) ? reason : null,
                    current.version(),
                    current.createdAt(),
                    now);
        });
    }

    private void validateReferences(TeamId team, EndpointSpec spec) {
        if (spec.auth().secretRef() != null) {
            var metadata = secrets.find(team, spec.auth().secretRef());
            if (metadata.isEmpty()) {
                throw new dev.kairos.domain.error.ValidationFailedException(
                        ErrorCode.SECRET_NOT_FOUND,
                        "auth.secretRef refers to a secret that does not exist.",
                        List.of());
            }
            CredentialValue.Kind expected = switch (spec.auth().type()) {
                case BASIC -> CredentialValue.Kind.BASIC;
                case BEARER -> CredentialValue.Kind.BEARER;
                case API_KEY -> CredentialValue.Kind.API_KEY;
                case OAUTH2_CLIENT_CREDENTIALS -> CredentialValue.Kind.OAUTH2_CLIENT;
                case NONE -> throw new IllegalStateException("NONE auth cannot reference a secret");
            };
            if (metadata.get().kind() != expected) {
                throw dev.kairos.domain.error.ValidationFailedException.of("auth.secretRef", "kind-mismatch");
            }
        }
        if (spec.caBundle() != null && caBundles.find(team, spec.caBundle()).isEmpty()) {
            throw dev.kairos.domain.error.ValidationFailedException.of("caBundle", "not-found");
        }
    }

    private void lock(TeamId team) {
        control.sql("SELECT team_id FROM scheduler.teams WHERE team_id = :team FOR UPDATE")
                .param("team", team.value())
                .query(UUID.class)
                .single();
    }

    private Endpoint map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Endpoint(
                rs.getObject("endpoint_id", UUID.class),
                rs.getString("name"),
                json.readValue(rs.getString("spec"), EndpointSpec.class),
                rs.getString("status"),
                rs.getString("status_reason"),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private void record(TeamId team, String actor, String action, UUID id, long version) {
        audit.record(new AuditWriter.Entry(team, actor, action, "endpoint", id.toString(), Map.of("version", version)));
    }

    private static void checkSize(String serialized) {
        if (serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536) {
            throw dev.kairos.domain.error.ValidationFailedException.of("spec", "length");
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException("The endpoint does not exist.");
    }
}
