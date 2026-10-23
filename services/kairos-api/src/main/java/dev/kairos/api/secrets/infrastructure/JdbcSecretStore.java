package dev.kairos.api.secrets.infrastructure;

import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.secrets.application.CredentialValue;
import dev.kairos.api.secrets.application.SecretStore;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.NotFoundException;
import dev.kairos.domain.id.TeamId;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.keyset.SealedKeyset;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Team-row locks serialize mutations and reference checks; all mutation audits share the transaction. */
public final class JdbcSecretStore implements SecretStore {
    private static final String METADATA = """
            SELECT name, MIN(kind) AS kind, MAX(version) AS version, MIN(created_at) AS created_at
              FROM scheduler.team_secrets WHERE team_id = :team AND disabled_at IS NULL
            """;
    private final JdbcClient control;
    private final ControlTransactions transactions;
    private final AuditWriter audit;

    public JdbcSecretStore(JdbcClient control, ControlTransactions transactions, AuditWriter audit) {
        this.control = control;
        this.transactions = transactions;
        this.audit = audit;
    }

    @Override
    public Optional<SealedKeyset> keyset(TeamId team) {
        return control.sql(
                        "SELECT encrypted_keyset, kek_uri FROM scheduler.team_keysets WHERE team_id = :team AND purpose = 'SECRETS'")
                .param("team", team.value())
                .query((rs, row) -> new SealedKeyset(rs.getBytes(1), rs.getString(2)))
                .optional();
    }

    @Override
    public SealedKeyset ensureKeyset(TeamId team, SealedKeyset candidate, Instant now) {
        control.sql("""
                INSERT INTO scheduler.team_keysets (team_id, purpose, encrypted_keyset, kek_uri, updated_at)
                VALUES (:team, 'SECRETS', :ciphertext, :uri, :now) ON CONFLICT (team_id, purpose) DO NOTHING
                """)
                .param("team", team.value())
                .param("ciphertext", candidate.ciphertext())
                .param("uri", candidate.kekUri())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
        return keyset(team).orElseThrow();
    }

    @Override
    public Optional<Metadata> find(TeamId team, String name) {
        return query(team, METADATA + " AND name = :name GROUP BY name")
                .param("name", name)
                .query((rs, row) -> new Metadata(
                        rs.getString("name"),
                        CredentialValue.Kind.valueOf(rs.getString("kind")),
                        rs.getInt("version"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public Metadata create(
            TeamId team, String actor, String name, CredentialValue.Kind kind, byte[] ciphertext, Instant now) {
        return transactions.execute(jdbc -> {
            lock(team);
            if (control.sql("SELECT 1 FROM scheduler.team_secrets WHERE team_id = :team AND name = :name LIMIT 1")
                    .param("team", team.value())
                    .param("name", name)
                    .query(Integer.class)
                    .optional()
                    .isPresent()) {
                throw new ConflictException(
                        ErrorCode.SECRET_EXISTS, "A secret with this name already exists. Use PUT to add a version.");
            }
            insert(team, name, kind, 1, ciphertext, now);
            record(team, actor, "SECRET_CREATED", name, 1);
            return new Metadata(name, kind, 1, now);
        });
    }

    @Override
    public Optional<Metadata> rotate(TeamId team, String actor, Metadata expected, byte[] ciphertext, Instant now) {
        return transactions.execute(jdbc -> {
            lock(team);
            Optional<Metadata> current = find(team, expected.name());
            if (current.isEmpty() || !current.get().equals(expected)) return Optional.empty();
            insert(team, expected.name(), expected.kind(), expected.version() + 1, ciphertext, now);
            record(team, actor, "SECRET_ROTATED", expected.name(), expected.version() + 1);
            return Optional.of(
                    new Metadata(expected.name(), expected.kind(), expected.version() + 1, expected.createdAt()));
        });
    }

    @Override
    public List<Metadata> list(TeamId team, @Nullable String after, int limit) {
        String sql =
                METADATA + (after == null ? "" : " AND name > :after") + " GROUP BY name ORDER BY name LIMIT :limit";
        var query = query(team, sql).param("limit", limit);
        if (after != null) query = query.param("after", after);
        return query.query((rs, row) -> new Metadata(
                        rs.getString("name"),
                        CredentialValue.Kind.valueOf(rs.getString("kind")),
                        rs.getInt("version"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public void delete(TeamId team, String actor, String name) {
        transactions.execute(jdbc -> {
            lock(team);
            if (find(team, name).isEmpty()) throw new NotFoundException("The secret does not exist.");
            Optional<String> endpoint = control.sql("""
                    SELECT endpoint.name FROM scheduler.api_endpoints endpoint
                     WHERE endpoint.team_id = :team
                       AND (endpoint.spec #>> '{auth,secretRef}' = :name OR EXISTS (
                           SELECT 1 FROM scheduler.api_endpoint_versions version
                            WHERE version.endpoint_id = endpoint.endpoint_id
                              AND version.spec #>> '{auth,secretRef}' = :name))
                     ORDER BY endpoint.name LIMIT 1
                    """)
                    .param("team", team.value())
                    .param("name", name)
                    .query(String.class)
                    .optional();
            if (endpoint.isPresent())
                throw new ConflictException(
                        ErrorCode.SECRET_IN_USE, "The secret is used by endpoint " + endpoint.get() + ".");
            control.sql("DELETE FROM scheduler.team_secrets WHERE team_id = :team AND name = :name")
                    .param("team", team.value())
                    .param("name", name)
                    .update();
            record(team, actor, "SECRET_DELETED", name, 0);
            return null;
        });
    }

    private void lock(TeamId team) {
        control.sql("SELECT team_id FROM scheduler.teams WHERE team_id = :team FOR UPDATE")
                .param("team", team.value())
                .query(java.util.UUID.class)
                .single();
    }

    private JdbcClient.StatementSpec query(TeamId team, String sql) {
        return control.sql(sql).param("team", team.value());
    }

    private void insert(
            TeamId team, String name, CredentialValue.Kind kind, int version, byte[] ciphertext, Instant now) {
        control.sql("""
                INSERT INTO scheduler.team_secrets (team_id, name, version, kind, ciphertext, created_at)
                VALUES (:team, :name, :version, :kind, :ciphertext, :now)
                """)
                .param("team", team.value())
                .param("name", name)
                .param("version", version)
                .param("kind", kind.name())
                .param("ciphertext", ciphertext)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void record(TeamId team, String actor, String action, String name, int version) {
        audit.record(new AuditWriter.Entry(
                team, actor, action, "secret", name, version == 0 ? Map.of() : Map.of("version", version)));
    }
}
