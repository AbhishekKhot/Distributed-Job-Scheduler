package dev.kairos.migrations.schema;

import java.util.Objects;
import java.util.Properties;
import javax.sql.DataSource;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * One database the job migrates: its kind, a name for logs and how to connect to it.
 *
 * <p>The data source opens a new connection per use, which suits a job that runs a handful of statements and exits.
 *
 * @param kind the kind of database
 * @param name a log-safe name such as {@code control} or {@code cell 0}; never the URL
 * @param dataSource connects as the schema owner
 */
public record MigrationTarget(DatabaseKind kind, String name, DataSource dataSource) {

    private static final String CONNECT_TIMEOUT_SECONDS = "10";

    /** Checks that every component is present. */
    public MigrationTarget {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Creates a target that connects with a JDBC URL and the schema owner's credentials.
     *
     * @param kind the kind of database
     * @param name a log-safe name
     * @param url a PostgreSQL JDBC URL without credentials
     * @param username the schema owner
     * @param password its password
     * @return the target
     */
    public static MigrationTarget of(DatabaseKind kind, String name, String url, String username, String password) {
        Properties connection = new Properties();
        connection.setProperty("user", username);
        connection.setProperty("password", password);
        connection.setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS);
        connection.setProperty("ApplicationName", "kairos-migrations");
        return new MigrationTarget(kind, name, new SimpleDriverDataSource(new Driver(), url, connection));
    }

    /** Leaves the data source out: its connection properties hold the password. */
    @Override
    public String toString() {
        return "MigrationTarget[kind=" + kind + ", name=" + name + "]";
    }
}
