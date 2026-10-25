package dev.kairos.api;

import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.security.keyset.JdbcPlatformKeysetStore;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.LocalKeyEncryptionKey;
import dev.kairos.security.keyset.PlatformKeysetBootstrap;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.RepositoryFiles;
import dev.kairos.testsupport.TestIds;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Test fixtures of the API: the real control and cell schemas on the shared Postgres container, the platform JWT
 * keyset, and the generated secrets the application needs.
 *
 * <p>The schema comes from the migration scripts in {@code jobs/kairos-migrations}, applied once per test JVM as the
 * schema owner; {@code cell_map} gets the local layout. The key encryption key, API key pepper and cursor key are
 * random per test JVM, so no secret is committed. The JWT signing keyset is created the way the migrations job does
 * it, sealed with that key.
 */
public final class ApiTestFixtures {

    /** The local key encryption key of this test run. */
    public static final String LOCAL_KEK = randomHex();

    /** The API key pepper of this test run. */
    public static final String API_KEY_PEPPER = randomHex();

    /** The cursor key of this test run. */
    public static final String CURSOR_KEY = randomHex();

    private static final String MIGRATIONS = "jobs/kairos-migrations/src/main/resources/db/";
    // The day-partition script adds the default partition of audit_log, which the accounts audit entries need.
    private static final List<String> CONTROL_SCRIPTS = List.of(
            MIGRATIONS + "control/V2026_10_20_0900__create_control_schema.sql",
            MIGRATIONS + "control/V2026_10_20_0905__add_day_partitions.sql",
            MIGRATIONS + "control/V2026_10_23_1530__signing_key_overlap.sql");
    private static final List<String> CELL_SCRIPTS = List.of(
            MIGRATIONS + "cell/V2026_10_20_0910__create_cell_schema.sql",
            MIGRATIONS + "cell/V2026_10_21_0900__add_dead_letter_fence.sql",
            MIGRATIONS + "cell/V2026_10_20_0915__partition_cell_tables.sql",
            MIGRATIONS + "cell/V2026_10_25_0900__workflow_task_pins.sql",
            MIGRATIONS + "cell/V2026_10_25_1100__workflow_review_expiry.sql");
    private static final String APP_ROLE_PLACEHOLDER = "${appRole}";
    private static final String SEED_CELL = """
            INSERT INTO scheduler.cell_map (cell_id, shard_from, shard_to, datasource_ref, status)
            VALUES (:cellId, :shardFrom, :shardTo, :ref, 'ACTIVE')
            ON CONFLICT (cell_id) DO NOTHING
            """;
    // Satisfies the column check only; no test logs in with a password.
    private static final String PLACEHOLDER_PASSWORD_HASH = "$argon2id$placeholder";

    private static boolean ready;

    private ApiTestFixtures() {}

    /**
     * Applies the schemas, seeds {@code cell_map} and creates the JWT keyset, once per test JVM.
     *
     * @return the shared container
     */
    public static synchronized KairosPostgres ensureDatabases() {
        KairosPostgres postgres = KairosPostgres.get();
        if (ready) {
            return postgres;
        }
        for (String script : CONTROL_SCRIPTS) {
            apply(postgres.controlOwnerDataSource(), script, postgres.appUsername());
        }
        JdbcClient control = JdbcClient.create(postgres.controlDataSource());
        for (int cell = 0; cell < KairosPostgres.CELL_COUNT; cell++) {
            for (String script : CELL_SCRIPTS) {
                apply(postgres.cellOwnerDataSource(cell), script, postgres.appUsername());
            }
            control.sql(SEED_CELL)
                    .param("cellId", (short) cell)
                    .param("shardFrom", (short) (cell * 128))
                    .param("shardTo", (short) (cell * 128 + 127))
                    .param("ref", Database.cellName(cell))
                    .update();
        }
        KeysetService keysets = new KeysetService(
                LocalKeyEncryptionKey.forLocalProfile(LOCAL_KEK, true),
                new SecureRandom(),
                Duration.ofMinutes(10),
                100);
        new PlatformKeysetBootstrap(new JdbcPlatformKeysetStore(control), keysets, Clock.systemUTC())
                .ensureJwtSigningKeyset();
        ready = true;
        return postgres;
    }

    /**
     * Registers the secrets of this test run and prepares the databases, for a {@code @DynamicPropertySource}.
     *
     * @param registry the property registry
     */
    public static void register(DynamicPropertyRegistry registry) {
        ensureDatabases();
        registry.add("kairos.security.kek.local-key", () -> LOCAL_KEK);
        registry.add("kairos.security.api-key.pepper", () -> API_KEY_PEPPER);
        registry.add("kairos.api.cursor.hmac-key", () -> CURSOR_KEY);
    }

    /**
     * Returns a client of the control database as the application role.
     *
     * @return the client
     */
    public static JdbcClient control() {
        return JdbcClient.create(ensureDatabases().controlDataSource());
    }

    /**
     * Returns a client of a cell database as the application role.
     *
     * @param cell the cell number
     * @return the client
     */
    public static JdbcClient cell(int cell) {
        return JdbcClient.create(ensureDatabases().cellDataSource(cell));
    }

    /**
     * Inserts a team.
     *
     * @return the team
     */
    public static TeamId insertTeam() {
        TeamId team = TestIds.teamId();
        Instant now = Instant.now();
        control()
                .sql("""
                        INSERT INTO scheduler.teams (team_id, slug, created_at, updated_at)
                        VALUES (:teamId, :slug, :now, :now)
                        """)
                .param("teamId", team.value())
                .param("slug", "t" + randomHex().substring(0, 20))
                .param("now", utc(now))
                .update();
        return team;
    }

    /**
     * Inserts a user.
     *
     * @param team the user's team, or {@code null}
     * @return the user
     */
    public static UserId insertUser(@Nullable TeamId team) {
        UserId user = TestIds.userId();
        Instant now = Instant.now();
        control()
                .sql("""
                        INSERT INTO scheduler.users
                            (user_id, team_id, email, password_hash, password_changed_at, created_at, updated_at)
                        VALUES (:userId, :teamId, :email, :hash, :now, :now, :now)
                        """)
                .param("userId", user.value())
                .param("teamId", team == null ? null : team.value(), java.sql.Types.OTHER)
                .param("email", "u" + randomHex().substring(0, 16) + "@example.com")
                .param("hash", PLACEHOLDER_PASSWORD_HASH)
                .param("now", utc(now))
                .update();
        return user;
    }

    /**
     * Inserts an active login session (one refresh-token row) and returns its family id, the token's {@code sid}.
     *
     * @param user the user
     * @return the family id
     */
    public static UUID insertSession(UserId user) {
        UUID family = UUID.randomUUID();
        Instant now = Instant.now();
        byte[] hash = new byte[32];
        new SecureRandom().nextBytes(hash);
        control()
                .sql("""
                        INSERT INTO scheduler.user_sessions
                            (session_id, user_id, family_id, token_hash, status, created_at, expires_at)
                        VALUES (:sessionId, :userId, :familyId, :hash, 'ACTIVE', :now, :expires)
                        """)
                .param("sessionId", UUID.randomUUID())
                .param("userId", user.value())
                .param("familyId", family)
                .param("hash", hash)
                .param("now", utc(now))
                .param("expires", utc(now.plus(Duration.ofDays(1))))
                .update();
        return family;
    }

    /**
     * Inserts an API key row.
     *
     * @param keyId the public key id
     * @param team the team
     * @param createdBy the creating user
     * @param secretHash the stored hash
     * @param pepperVersion the pepper version
     */
    public static void insertApiKey(String keyId, TeamId team, UserId createdBy, byte[] secretHash, int pepperVersion) {
        control()
                .sql("""
                        INSERT INTO scheduler.api_keys
                            (key_id, team_id, name, secret_hash, pepper_version, created_by, created_at)
                        VALUES (:keyId, :teamId, 'test', :hash, :version, :createdBy, :now)
                        """)
                .param("keyId", keyId)
                .param("teamId", team.value())
                .param("hash", secretHash)
                .param("version", (short) pepperVersion)
                .param("createdBy", createdBy.value())
                .param("now", utc(Instant.now()))
                .update();
    }

    /**
     * Returns an instant as a {@code timestamptz} parameter.
     *
     * @param instant the instant
     * @return the UTC offset date-time
     */
    public static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Returns 32 random bytes as hex.
     *
     * @return 64 hex characters
     */
    public static String randomHex() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void apply(DataSource owner, String script, String appRole) {
        String sql;
        try {
            sql = Files.readString(RepositoryFiles.resolve(script), StandardCharsets.UTF_8)
                    .replace(APP_ROLE_PLACEHOLDER, appRole);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (Connection connection = owner.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not apply " + script, e);
        }
    }
}
