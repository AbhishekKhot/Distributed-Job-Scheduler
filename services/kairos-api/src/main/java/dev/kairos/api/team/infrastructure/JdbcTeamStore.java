package dev.kairos.api.team.infrastructure;

import dev.kairos.api.team.application.Member;
import dev.kairos.api.team.application.MemberKey;
import dev.kairos.api.team.application.TeamStore;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.keyset.KeyPurpose;
import dev.kairos.security.keyset.SealedKeyset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link TeamStore} on {@code teams}, {@code team_keysets} and {@code users} in the control database. Lookups use the
 * primary keys, {@code teams_slug_uk}, {@code users_email_uk} and {@code users_team_created_ix}. Statements run in the
 * caller's transaction when there is one. Thread-safe.
 */
public final class JdbcTeamStore implements TeamStore {

    private static final String FIND_MEMBERSHIP = "SELECT team_id FROM scheduler.users WHERE user_id = :userId";
    private static final String LOCK_USER = FIND_MEMBERSHIP + " FOR UPDATE";
    private static final String INSERT_TEAM = """
            INSERT INTO scheduler.teams (team_id, slug, quotas, created_at, updated_at)
            VALUES (:teamId, :slug, CAST(:quotas AS jsonb), :now, :now)
            ON CONFLICT (slug) DO NOTHING
            """;
    private static final String INSERT_KEYSET = """
            INSERT INTO scheduler.team_keysets (team_id, purpose, encrypted_keyset, kek_uri, updated_at)
            VALUES (:teamId, :purpose, :keyset, :kekUri, :now)
            """;
    private static final String JOIN = """
            UPDATE scheduler.users
               SET team_id = :teamId, updated_at = :now
             WHERE user_id = :userId
            """;
    private static final String FIND_TEAM =
            "SELECT team_id, slug, quotas::text AS quotas, created_at FROM scheduler.teams WHERE team_id = :teamId";
    private static final String MEMBERS_FIRST = """
            SELECT user_id, email, created_at
              FROM scheduler.users
             WHERE team_id = :teamId
             ORDER BY created_at DESC, user_id DESC
             LIMIT :limit
            """;
    private static final String MEMBERS_AFTER = """
            SELECT user_id, email, created_at
              FROM scheduler.users
             WHERE team_id = :teamId
               AND (created_at, user_id) < (:afterCreated, :afterUser)
             ORDER BY created_at DESC, user_id DESC
             LIMIT :limit
            """;
    // Only a user without a team is taken, in one statement: of two teams adding the same user, one wins.
    private static final String ADD_BY_EMAIL = """
            UPDATE scheduler.users
               SET team_id = :teamId, updated_at = :now
             WHERE email = :email
               AND team_id IS NULL
            RETURNING user_id, email, created_at
            """;
    private static final String FIND_BY_EMAIL =
            "SELECT user_id, email, team_id, created_at FROM scheduler.users WHERE email = :email";
    // Locked in a fixed order, so concurrent removals in one team never deadlock.
    private static final String LOCK_MEMBERS = """
            SELECT user_id
              FROM scheduler.users
             WHERE team_id = :teamId
             ORDER BY user_id
               FOR UPDATE
            """;
    private static final String LEAVE = """
            UPDATE scheduler.users
               SET team_id = NULL, updated_at = :now
             WHERE user_id = :userId
               AND team_id = :teamId
            """;

    private final JdbcClient control;
    private final JsonMapper json;

    /**
     * Creates the store.
     *
     * @param control the control database's client
     * @param json reads and writes the quotas
     */
    public JdbcTeamStore(JdbcClient control, JsonMapper json) {
        this.control = Objects.requireNonNull(control, "control");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public Optional<Membership> lockUser(UserId user) {
        return membership(LOCK_USER, user);
    }

    @Override
    public Optional<Membership> findMembership(UserId user) {
        return membership(FIND_MEMBERSHIP, user);
    }

    private Optional<Membership> membership(String sql, UserId user) {
        return control.sql(sql)
                .param("userId", user.value())
                .query((rs, row) -> new Membership(team(rs)))
                .optional();
    }

    @Override
    public boolean insertTeam(NewTeam team) {
        return control.sql(INSERT_TEAM)
                        .param("teamId", team.id().value())
                        .param("slug", team.slug())
                        .param("quotas", json.writeValueAsString(team.quotas().asMap()))
                        .param("now", utc(team.createdAt()))
                        .update()
                == 1;
    }

    @Override
    public void insertKeyset(TeamId team, KeyPurpose purpose, SealedKeyset keyset, Instant now) {
        control.sql(INSERT_KEYSET)
                .param("teamId", team.value())
                .param("purpose", purpose.name())
                .param("keyset", keyset.ciphertext())
                .param("kekUri", keyset.kekUri())
                .param("now", utc(now))
                .update();
    }

    @Override
    public void join(UserId user, TeamId team, Instant now) {
        control.sql(JOIN)
                .param("teamId", team.value())
                .param("userId", user.value())
                .param("now", utc(now))
                .update();
    }

    @Override
    public Optional<StoredTeam> findTeam(TeamId team) {
        return control.sql(FIND_TEAM)
                .param("teamId", team.value())
                .query((rs, row) -> new StoredTeam(
                        new TeamId(rs.getObject("team_id", UUID.class)),
                        rs.getString("slug"),
                        quotas(rs.getString("quotas")),
                        instant(rs, "created_at")))
                .optional();
    }

    @Override
    public List<Member> members(TeamId team, @Nullable MemberKey after, int limit) {
        JdbcClient.StatementSpec statement = after == null
                ? control.sql(MEMBERS_FIRST)
                : control.sql(MEMBERS_AFTER)
                        .param("afterCreated", utc(after.createdAt()))
                        .param("afterUser", after.user().value());
        return statement
                .param("teamId", team.value())
                .param("limit", limit)
                .query((rs, row) -> member(rs))
                .list();
    }

    @Override
    public AddOutcome addByEmail(TeamId team, String email, Instant now) {
        Optional<Member> added = control.sql(ADD_BY_EMAIL)
                .param("teamId", team.value())
                .param("email", email)
                .param("now", utc(now))
                .query((rs, row) -> member(rs))
                .optional();
        if (added.isPresent()) {
            return new AddOutcome.Added(added.get());
        }
        return control.sql(FIND_BY_EMAIL)
                .param("email", email)
                .query((rs, row) -> {
                    TeamId current = team(rs);
                    return team.equals(current)
                            ? new AddOutcome.AlreadyMember(member(rs))
                            : (AddOutcome) new AddOutcome.InAnotherTeam();
                })
                .optional()
                .orElseGet(AddOutcome.UnknownUser::new);
    }

    @Override
    public List<UserId> lockMembers(TeamId team) {
        return control.sql(LOCK_MEMBERS)
                .param("teamId", team.value())
                .query((rs, row) -> new UserId(rs.getObject("user_id", UUID.class)))
                .list();
    }

    @Override
    public void leave(UserId user, TeamId team, Instant now) {
        control.sql(LEAVE)
                .param("userId", user.value())
                .param("teamId", team.value())
                .param("now", utc(now))
                .update();
    }

    private Map<String, Long> quotas(@Nullable String stored) {
        Map<String, Long> quotas = new HashMap<>();
        if (stored == null) {
            return quotas;
        }
        JsonNode node = json.readTree(stored);
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (field.getValue().isIntegralNumber() && field.getValue().canConvertToLong()) {
                quotas.put(field.getKey(), field.getValue().asLong());
            }
        }
        return quotas;
    }

    private static Member member(ResultSet rs) throws SQLException {
        return new Member(
                new UserId(rs.getObject("user_id", UUID.class)), rs.getString("email"), instant(rs, "created_at"));
    }

    private static @Nullable TeamId team(ResultSet rs) throws SQLException {
        UUID team = rs.getObject("team_id", UUID.class);
        return team == null ? null : new TeamId(team);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return Objects.requireNonNull(rs.getObject(column, OffsetDateTime.class), column)
                .toInstant();
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
