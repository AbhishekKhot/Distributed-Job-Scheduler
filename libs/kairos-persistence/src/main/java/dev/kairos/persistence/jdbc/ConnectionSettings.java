package dev.kairos.persistence.jdbc;

import java.util.Objects;

/**
 * Where and as whom to connect to one database.
 *
 * <p>Credentials come from the environment ({@code KAIROS_*} variables, Secret Manager in production) and never from
 * the URL. The password is left out of {@link #toString()} so the settings can never end up in a log line.
 *
 * @param url a PostgreSQL JDBC URL, for example {@code jdbc:postgresql://postgres:5432/kairos_cell0}
 * @param username the role to connect as; the services use {@code sched_app} (DML only)
 * @param password the role's password
 * @param maximumPoolSize the size of this database's connection pool
 */
public record ConnectionSettings(String url, String username, String password, int maximumPoolSize) {

    private static final String URL_PREFIX = "jdbc:postgresql://";

    /**
     * Validates the settings.
     *
     * @throws IllegalArgumentException if the URL is not a PostgreSQL JDBC URL, the user name or password is blank,
     *     or the pool size is not positive
     */
    public ConnectionSettings {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        if (!url.startsWith(URL_PREFIX)) {
            throw new IllegalArgumentException("Only PostgreSQL JDBC URLs are supported");
        }
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalArgumentException("User name and password are required");
        }
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("The pool size must be positive");
        }
    }

    /** Leaves the password out. */
    @Override
    public String toString() {
        return "ConnectionSettings[url=" + url + ", username=" + username + ", password=<redacted>, maximumPoolSize="
                + maximumPoolSize + "]";
    }
}
