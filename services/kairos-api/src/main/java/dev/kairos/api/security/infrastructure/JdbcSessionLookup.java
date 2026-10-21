package dev.kairos.api.security.infrastructure;

import dev.kairos.api.security.SessionLookup;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link SessionLookup} on {@code users} and {@code user_sessions}: one indexed read by user id plus an
 * {@code EXISTS} on the session family ({@code user_sessions_family_ix}). Thread-safe.
 */
public final class JdbcSessionLookup implements SessionLookup {

    private static final String FIND = """
            SELECT u.team_id,
                   u.status,
                   EXISTS (SELECT 1
                             FROM scheduler.user_sessions s
                            WHERE s.family_id = :sessionId
                              AND s.user_id = :userId
                              AND s.status = 'ACTIVE'
                              AND s.expires_at > :now) AS session_active
              FROM scheduler.users u
             WHERE u.user_id = :userId
            """;

    private final JdbcClient control;

    /**
     * Creates the lookup.
     *
     * @param control the control database's client
     */
    public JdbcSessionLookup(JdbcClient control) {
        this.control = Objects.requireNonNull(control, "control");
    }

    @Override
    public Optional<SessionState> find(UserId user, UUID sessionId, Instant now) {
        return control.sql(FIND)
                .param("userId", user.value())
                .param("sessionId", sessionId)
                .param("now", SqlTimes.utc(now))
                .query((rs, row) -> {
                    UUID team = rs.getObject("team_id", UUID.class);
                    return new SessionState(
                            rs.getBoolean("session_active"),
                            "ACTIVE".equals(rs.getString("status")),
                            team == null ? null : new TeamId(team));
                })
                .optional();
    }
}
