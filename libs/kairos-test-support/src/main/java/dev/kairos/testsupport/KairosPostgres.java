package dev.kairos.testsupport;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.sql.DataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL test container laid out exactly like the local stack.
 *
 * <p>The container runs the same image as the {@code postgres} service in {@code deploy/compose/docker-compose.yml}
 * and the same init script, {@code deploy/compose/postgres/01-init-databases.sh}. That gives three databases,
 * {@code kairos_control}, {@code kairos_cell0} and {@code kairos_cell1}, each with a {@code scheduler} schema owned by
 * {@value #OWNER_USERNAME} (DDL, used by migration tests) and DML-only access for {@value #APP_USERNAME} (used by
 * everything else). The superuser, owner and application passwords are generated for every run.
 *
 * <p>One container is started lazily per JVM, on the first call to {@link #get()}, and shared by every test class of
 * that run. It is removed by the Testcontainers reaper when the JVM exits; it is never reused across runs. Tests that
 * share it must create uniquely named data or clean up after themselves.
 *
 * <p>Thread-safe.
 */
public final class KairosPostgres {

    /** The image reference, identical to the {@code postgres} service in compose. */
    public static final String IMAGE =
            "postgres:18.6@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336";

    /** The role the services use: DML on tables in schema {@code scheduler}, no DDL. */
    public static final String APP_USERNAME = "sched_app";

    /** The role that owns schema {@code scheduler}; only migrations connect with it. */
    public static final String OWNER_USERNAME = "sched_owner";

    /** The number of cell databases, {@code kairos_cell0} and {@code kairos_cell1}. */
    public static final int CELL_COUNT = 2;

    private static final String CONTROL_DATABASE = "kairos_control";
    private static final String CELL_DATABASE_PREFIX = "kairos_cell";
    private static final String INIT_SCRIPT = "deploy/compose/postgres/01-init-databases.sh";
    private static final String INIT_SCRIPT_IN_CONTAINER = "/docker-entrypoint-initdb.d/01-init-databases.sh";
    private static final int POSTGRES_PORT = 5432;
    private static final int POOL_SIZE = 4;
    private static final long CONNECTION_TIMEOUT_MS = 10_000;
    private static final Lazy<KairosPostgres> INSTANCE = new Lazy<>(KairosPostgres::start);

    private final String jdbcUrlPrefix;
    private final String ownerPassword;
    private final String appPassword;
    private final ConcurrentMap<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();

    private KairosPostgres(String jdbcUrlPrefix, String ownerPassword, String appPassword) {
        this.jdbcUrlPrefix = jdbcUrlPrefix;
        this.ownerPassword = ownerPassword;
        this.appPassword = appPassword;
    }

    /**
     * Returns the shared container, starting it on first use.
     *
     * @return the running container
     * @throws IllegalStateException if the container cannot be started, now or in an earlier attempt of this run
     */
    public static KairosPostgres get() {
        return INSTANCE.get();
    }

    private static KairosPostgres start() {
        String superuserPassword = TestSecrets.randomHex();
        String ownerPassword = TestSecrets.randomHex();
        String appPassword = TestSecrets.randomHex();
        PostgreSQLContainer container = new PostgreSQLContainer(
                        PinnedImage.parse(IMAGE).toDockerImageName("postgres"))
                .withUsername("postgres")
                .withPassword(superuserPassword)
                .withDatabaseName("postgres")
                .withEnv("POSTGRES_INITDB_ARGS", "--auth-host=scram-sha-256")
                .withEnv("KAIROS_DB_OWNER_PASSWORD", ownerPassword)
                .withEnv("KAIROS_DB_APP_PASSWORD", appPassword)
                // Copied rather than bind-mounted: the build itself runs in a container, so a host path would not
                // resolve on the Docker daemon.
                .withCopyFileToContainer(
                        MountableFile.forHostPath(RepositoryFiles.resolve(INIT_SCRIPT), 0555), INIT_SCRIPT_IN_CONTAINER)
                // Same settings as compose, plus fsync=off: test data never needs to survive a crash.
                .withCommand(
                        "postgres",
                        "-c",
                        "max_connections=300",
                        "-c",
                        "password_encryption=scram-sha-256",
                        "-c",
                        "fsync=off");
        container.start();
        String prefix = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(POSTGRES_PORT) + "/";
        return new KairosPostgres(prefix, ownerPassword, appPassword);
    }

    /**
     * Returns the JDBC URL of the control database.
     *
     * @return a URL such as {@code jdbc:postgresql://host:port/kairos_control}
     */
    public String controlJdbcUrl() {
        return jdbcUrlPrefix + CONTROL_DATABASE;
    }

    /**
     * Returns the JDBC URL of a cell database.
     *
     * @param cell the cell number, 0 or 1
     * @return a URL such as {@code jdbc:postgresql://host:port/kairos_cell0}
     * @throws IllegalArgumentException if the cell does not exist
     */
    public String cellJdbcUrl(int cell) {
        return jdbcUrlPrefix + cellDatabase(cell);
    }

    /**
     * Returns the user name of the application role.
     *
     * @return {@value #APP_USERNAME}
     */
    public String appUsername() {
        return APP_USERNAME;
    }

    /**
     * Returns the password of the application role, generated for this run.
     *
     * @return the password; never log it
     */
    public String appPassword() {
        return appPassword;
    }

    /**
     * Returns the user name of the schema owner role.
     *
     * @return {@value #OWNER_USERNAME}
     */
    public String ownerUsername() {
        return OWNER_USERNAME;
    }

    /**
     * Returns the password of the schema owner role, generated for this run.
     *
     * @return the password; never log it
     */
    public String ownerPassword() {
        return ownerPassword;
    }

    /**
     * Returns a pooled data source for the control database, connecting as {@value #APP_USERNAME}.
     *
     * <p>The pool is shared by every caller in this JVM and must not be closed.
     *
     * @return the shared data source
     */
    public DataSource controlDataSource() {
        return dataSource(CONTROL_DATABASE, APP_USERNAME, appPassword);
    }

    /**
     * Returns a pooled data source for a cell database, connecting as {@value #APP_USERNAME}.
     *
     * <p>The pool is shared by every caller in this JVM and must not be closed.
     *
     * @param cell the cell number, 0 or 1
     * @return the shared data source
     * @throws IllegalArgumentException if the cell does not exist
     */
    public DataSource cellDataSource(int cell) {
        return dataSource(cellDatabase(cell), APP_USERNAME, appPassword);
    }

    /**
     * Returns a pooled data source for the control database, connecting as the schema owner {@value #OWNER_USERNAME}.
     * Intended for migration tests only.
     *
     * <p>The pool is shared by every caller in this JVM and must not be closed.
     *
     * @return the shared data source
     */
    public DataSource controlOwnerDataSource() {
        return dataSource(CONTROL_DATABASE, OWNER_USERNAME, ownerPassword);
    }

    /**
     * Returns a pooled data source for a cell database, connecting as the schema owner {@value #OWNER_USERNAME}.
     * Intended for migration tests only.
     *
     * <p>The pool is shared by every caller in this JVM and must not be closed.
     *
     * @param cell the cell number, 0 or 1
     * @return the shared data source
     * @throws IllegalArgumentException if the cell does not exist
     */
    public DataSource cellOwnerDataSource(int cell) {
        return dataSource(cellDatabase(cell), OWNER_USERNAME, ownerPassword);
    }

    private static String cellDatabase(int cell) {
        if (cell < 0 || cell >= CELL_COUNT) {
            throw new IllegalArgumentException("Cell must be between 0 and " + (CELL_COUNT - 1) + ", got " + cell);
        }
        return CELL_DATABASE_PREFIX + cell;
    }

    private DataSource dataSource(String database, String username, String password) {
        return dataSources.computeIfAbsent(database + "/" + username, key -> {
            HikariConfig config = new HikariConfig();
            config.setPoolName("kairos-test-" + database + "-" + username);
            config.setJdbcUrl(jdbcUrlPrefix + database);
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(POOL_SIZE);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
            return new HikariDataSource(config);
        });
    }
}
