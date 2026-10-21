package dev.kairos.api.accounts.infrastructure;

import dev.kairos.api.accounts.application.UserAccounts;
import dev.kairos.api.accounts.application.UserProfile;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link UserAccounts} on {@code users} (and {@code teams} for the profile) in the control database. Lookups use the
 * primary key or {@code users_email_uk}. Statements run in the caller's transaction when there is one. Thread-safe.
 */
public final class JdbcUserAccounts implements UserAccounts {

    private static final String INSERT = """
            INSERT INTO scheduler.users
                (user_id, team_id, email, password_hash, status, failed_logins, password_changed_at, created_at,
                 updated_at)
            VALUES (:userId, NULL, :email, :hash, 'ACTIVE', 0, :now, :now, :now)
            ON CONFLICT (email) DO NOTHING
            """;
    private static final String COLUMNS = "user_id, team_id, email, password_hash, status, failed_logins, locked_until";
    private static final String FIND_BY_EMAIL = "SELECT " + COLUMNS + " FROM scheduler.users WHERE email = :email";
    private static final String FIND_BY_ID = "SELECT " + COLUMNS + " FROM scheduler.users WHERE user_id = :userId";
    private static final String FIND_PROFILE = """
            SELECT u.user_id, u.email, u.team_id, t.slug, u.created_at
              FROM scheduler.users u
              LEFT JOIN scheduler.teams t ON t.team_id = u.team_id
             WHERE u.user_id = :userId
            """;
    // The smallint column caps the count; a count that high is locked for the maximum period anyway.
    private static final String INCREMENT_FAILURES = """
            UPDATE scheduler.users
               SET failed_logins = LEAST(failed_logins + 1, 32767), updated_at = :now
             WHERE user_id = :userId
            RETURNING failed_logins
            """;
    private static final String LOCK = """
            UPDATE scheduler.users
               SET locked_until = :lockedUntil, updated_at = :now
             WHERE user_id = :userId
            """;
    private static final String RECORD_LOGIN = """
            UPDATE scheduler.users
               SET failed_logins = 0, locked_until = NULL, last_login_at = :now, updated_at = :now,
                   password_hash = COALESCE(:hash, password_hash)
             WHERE user_id = :userId
            """;
    private static final String UPDATE_PASSWORD = """
            UPDATE scheduler.users
               SET password_hash = :hash, password_changed_at = :now, updated_at = :now
             WHERE user_id = :userId
            """;

    private final JdbcClient control;

    /**
     * Creates the store.
     *
     * @param control the control database's client
     */
    public JdbcUserAccounts(JdbcClient control) {
        this.control = Objects.requireNonNull(control, "control");
    }

    @Override
    public boolean insert(NewUser user) {
        return control.sql(INSERT)
                        .param("userId", user.id().value())
                        .param("email", user.email())
                        .param("hash", user.passwordHash())
                        .param("now", utc(user.createdAt()))
                        .update()
                == 1;
    }

    @Override
    public Optional<StoredUser> findByEmail(String email) {
        return control.sql(FIND_BY_EMAIL)
                .param("email", email)
                .query((rs, row) -> storedUser(rs))
                .optional();
    }

    @Override
    public Optional<StoredUser> findById(UserId user) {
        return control.sql(FIND_BY_ID)
                .param("userId", user.value())
                .query((rs, row) -> storedUser(rs))
                .optional();
    }

    @Override
    public Optional<UserProfile> findProfile(UserId user) {
        return control.sql(FIND_PROFILE)
                .param("userId", user.value())
                .query((rs, row) -> new UserProfile(
                        new UserId(rs.getObject("user_id", UUID.class)),
                        rs.getString("email"),
                        team(rs),
                        rs.getString("slug"),
                        Objects.requireNonNull(instant(rs, "created_at"))))
                .optional();
    }

    @Override
    public int incrementFailedLogins(UserId user, Instant now) {
        return control.sql(INCREMENT_FAILURES)
                .param("userId", user.value())
                .param("now", utc(now))
                .query(Integer.class)
                .single();
    }

    @Override
    public void lock(UserId user, Instant lockedUntil, Instant now) {
        control.sql(LOCK)
                .param("userId", user.value())
                .param("lockedUntil", utc(lockedUntil))
                .param("now", utc(now))
                .update();
    }

    @Override
    public void recordLogin(UserId user, Instant now, @Nullable String newPasswordHash) {
        control.sql(RECORD_LOGIN)
                .param("userId", user.value())
                .param("now", utc(now))
                .param("hash", newPasswordHash, Types.VARCHAR)
                .update();
    }

    @Override
    public void updatePassword(UserId user, String passwordHash, Instant now) {
        control.sql(UPDATE_PASSWORD)
                .param("userId", user.value())
                .param("hash", passwordHash)
                .param("now", utc(now))
                .update();
    }

    private static StoredUser storedUser(ResultSet rs) throws SQLException {
        return new StoredUser(
                new UserId(rs.getObject("user_id", UUID.class)),
                team(rs),
                rs.getString("email"),
                rs.getString("password_hash"),
                "ACTIVE".equals(rs.getString("status")),
                rs.getInt("failed_logins"),
                instant(rs, "locked_until"));
    }

    private static @Nullable TeamId team(ResultSet rs) throws SQLException {
        UUID team = rs.getObject("team_id", UUID.class);
        return team == null ? null : new TeamId(team);
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
