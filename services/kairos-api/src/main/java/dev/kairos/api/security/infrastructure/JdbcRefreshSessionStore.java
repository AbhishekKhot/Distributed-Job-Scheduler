package dev.kairos.api.security.infrastructure;

import dev.kairos.domain.id.UserId;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.token.RefreshSession;
import dev.kairos.security.token.RefreshSessionStatus;
import dev.kairos.security.token.RefreshSessionStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link RefreshSessionStore} on {@code user_sessions} (LLD section 2.2). It lives with the session check because both
 * read the same table: an access token's {@code sid} is the family id written here.
 *
 * <p>A rotation marks the presented row {@code ROTATED} only if it is still {@code ACTIVE} and inserts the next row in
 * the same control-database transaction, so two concurrent refreshes with one token cannot both succeed. Thread-safe.
 */
public final class JdbcRefreshSessionStore implements RefreshSessionStore {

    private static final String COLUMNS = """
            session_id, user_id, family_id, token_hash, status, created_at, expires_at, user_agent,
            host(ip_address) AS ip_address""";
    private static final String FIND =
            "SELECT " + COLUMNS + " FROM scheduler.user_sessions WHERE token_hash = :tokenHash";
    private static final String INSERT = """
            INSERT INTO scheduler.user_sessions
                (session_id, user_id, family_id, token_hash, status, created_at, expires_at, user_agent, ip_address)
            VALUES (:sessionId, :userId, :familyId, :tokenHash, :status, :createdAt, :expiresAt, :userAgent,
                    CAST(:ipAddress AS inet))
            """;
    private static final String MARK_ROTATED = """
            UPDATE scheduler.user_sessions
               SET status = 'ROTATED', last_used_at = :at
             WHERE session_id = :sessionId AND status = 'ACTIVE'
            """;
    private static final String REVOKE_FAMILY = """
            UPDATE scheduler.user_sessions
               SET status = 'REVOKED'
             WHERE family_id = :familyId AND status <> 'REVOKED'
            """;
    private static final String REVOKE_USER = """
            UPDATE scheduler.user_sessions
               SET status = 'REVOKED'
             WHERE user_id = :userId AND status <> 'REVOKED'
            """;
    private static final String REVOKE_USER_EXCEPT = """
            UPDATE scheduler.user_sessions
               SET status = 'REVOKED'
             WHERE user_id = :userId AND status <> 'REVOKED' AND family_id <> :keepFamilyId
            """;

    private final JdbcClient control;
    private final ControlTransactions transactions;

    /**
     * Creates the store.
     *
     * @param control the control database's client
     * @param transactions runs the rotation in one transaction
     */
    public JdbcRefreshSessionStore(JdbcClient control, ControlTransactions transactions) {
        this.control = Objects.requireNonNull(control, "control");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public Optional<RefreshSession> findByTokenHash(byte[] tokenHash) {
        return control.sql(FIND)
                .param("tokenHash", tokenHash)
                .query((rs, row) -> map(rs))
                .optional();
    }

    @Override
    public void insert(RefreshSession session) {
        insert(control, session);
    }

    @Override
    public boolean rotate(RefreshSession current, RefreshSession next, Instant at) {
        return transactions.execute(jdbc -> {
            int marked = jdbc.sql(MARK_ROTATED)
                    .param("sessionId", current.sessionId())
                    .param("at", SqlTimes.utc(at))
                    .update();
            if (marked != 1) {
                return false;
            }
            insert(jdbc, next);
            return true;
        });
    }

    @Override
    public int revokeFamily(UUID familyId) {
        return control.sql(REVOKE_FAMILY).param("familyId", familyId).update();
    }

    @Override
    public int revokeUser(UserId user, @Nullable UUID keepFamilyId) {
        if (keepFamilyId == null) {
            return control.sql(REVOKE_USER).param("userId", user.value()).update();
        }
        return control.sql(REVOKE_USER_EXCEPT)
                .param("userId", user.value())
                .param("keepFamilyId", keepFamilyId)
                .update();
    }

    private static void insert(JdbcClient jdbc, RefreshSession session) {
        jdbc.sql(INSERT)
                .param("sessionId", session.sessionId())
                .param("userId", session.user().value())
                .param("familyId", session.familyId())
                .param("tokenHash", session.tokenHash())
                .param("status", session.status().name())
                .param("createdAt", SqlTimes.utc(session.createdAt()))
                .param("expiresAt", SqlTimes.utc(session.expiresAt()))
                .param("userAgent", session.userAgent(), java.sql.Types.VARCHAR)
                .param("ipAddress", session.ipAddress(), java.sql.Types.VARCHAR)
                .update();
    }

    private static RefreshSession map(ResultSet rs) throws SQLException {
        return new RefreshSession(
                rs.getObject("session_id", UUID.class),
                new UserId(rs.getObject("user_id", UUID.class)),
                rs.getObject("family_id", UUID.class),
                rs.getBytes("token_hash"),
                RefreshSessionStatus.valueOf(rs.getString("status")),
                Objects.requireNonNull(SqlTimes.instant(rs, "created_at")),
                Objects.requireNonNull(SqlTimes.instant(rs, "expires_at")),
                rs.getString("user_agent"),
                rs.getString("ip_address"));
    }
}
