package dev.kairos.api.apikeys.infrastructure;

import dev.kairos.api.apikeys.application.ApiKeyStore;
import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.apikey.IssuedApiKey;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** API keys and atomic security audit entries on the control database. Thread-safe. */
public final class JdbcApiKeyStore implements ApiKeyStore {
    private static final String INSERT = """
            INSERT INTO scheduler.api_keys
                (key_id, team_id, name, secret_hash, pepper_version, created_by, created_at, expires_at)
            VALUES (:keyId, :team, :name, :hash, :pepper, :creator, :now, :expires)
            """;
    // Matches the existing team/created_at/key_id index; no credentials are loaded.
    private static final String LIST = """
            SELECT key_id, name, created_by, created_at, expires_at, last_used_at, revoked_at
              FROM scheduler.api_keys
             WHERE team_id = :team
            """;
    private static final String REVOKE = """
            UPDATE scheduler.api_keys SET revoked_at = :now
             WHERE team_id = :team AND key_id = :keyId AND revoked_at IS NULL
            RETURNING key_id
            """;
    private static final String USE = """
            UPDATE scheduler.api_keys SET last_used_at = :now
             WHERE team_id = :team AND key_id = :keyId AND revoked_at IS NULL
               AND (expires_at IS NULL OR expires_at > :now)
               AND (last_used_at IS NULL OR last_used_at <= :before)
            """;
    private final JdbcClient control;
    private final ControlTransactions transactions;
    private final AuditWriter writer;

    public JdbcApiKeyStore(JdbcClient control, ControlTransactions transactions, AuditWriter writer) {
        this.control = control;
        this.transactions = transactions;
        this.writer = writer;
    }

    @Override
    public void create(
            TeamId team, UserId creator, String name, IssuedApiKey issued, @Nullable Instant expiresAt, Instant now) {
        transactions.execute(jdbc -> {
            control.sql(INSERT)
                    .param("keyId", issued.keyId())
                    .param("team", team.value())
                    .param("name", name)
                    .param("hash", issued.secretHash())
                    .param("pepper", (short) issued.pepperVersion())
                    .param("creator", creator.value())
                    .param("now", utc(now))
                    .param("expires", utc(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                    .update();
            audit(team, creator, issued.keyId(), "API_KEY_CREATED", now);
            return null;
        });
    }

    @Override
    public List<Metadata> list(TeamId team, @Nullable PageKey after, int limit) {
        String sql = LIST + (after == null ? "" : " AND (created_at, key_id) < (:afterTime, :afterId)")
                + " ORDER BY created_at DESC, key_id DESC LIMIT :limit";
        JdbcClient.StatementSpec query =
                control.sql(sql).param("team", team.value()).param("limit", limit);
        if (after != null) {
            query = query.param("afterTime", utc(after.createdAt())).param("afterId", after.keyId());
        }
        return query.query((rs, row) -> new Metadata(
                        rs.getString("key_id"),
                        rs.getString("name"),
                        new UserId(rs.getObject("created_by", UUID.class)),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        instant(rs, "expires_at"),
                        instant(rs, "last_used_at"),
                        instant(rs, "revoked_at")))
                .list();
    }

    @Override
    public boolean revoke(TeamId team, UserId actor, String keyId, Instant now) {
        return transactions.execute(jdbc -> {
            boolean changed = control.sql(REVOKE)
                    .param("team", team.value())
                    .param("keyId", keyId)
                    .param("now", utc(now))
                    .query(String.class)
                    .optional()
                    .isPresent();
            if (changed) {
                audit(team, actor, keyId, "API_KEY_REVOKED", now);
                return true;
            }
            return control.sql("SELECT 1 FROM scheduler.api_keys WHERE team_id = :team AND key_id = :keyId")
                    .param("team", team.value())
                    .param("keyId", keyId)
                    .query(Integer.class)
                    .optional()
                    .isPresent();
        });
    }

    @Override
    public void recordUse(TeamId team, String keyId, Instant now) {
        control.sql(USE)
                .param("team", team.value())
                .param("keyId", keyId)
                .param("now", utc(now))
                .param("before", utc(now.minusSeconds(60)))
                .update();
    }

    private void audit(TeamId team, UserId actor, String keyId, String action, Instant now) {
        writer.record(new AuditWriter.Entry(team, "user:" + actor.value(), action, "API_KEY", keyId, Map.of()));
    }

    private static @Nullable Instant instant(ResultSet rs, String name) throws SQLException {
        OffsetDateTime value = rs.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
