package dev.kairos.persistence.health;

import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.persistence.jdbc.Database;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports whether one database answers: it borrows a pooled connection and validates it within a timeout.
 *
 * <p>The indicators of a service are grouped by {@link #forRegistry} into one composite contributor with a child per
 * database ({@code control}, {@code cell-0}, {@code cell-1}, ...). A service lists the databases it cannot work without
 * in its readiness group, for example {@code management.endpoint.health.group.readiness.include=readinessState,
 * kairosDatabases} for all of them, or {@code kairosDatabases/control,kairosDatabases/cell-0} for some. A cell being
 * down then takes only the services that need it out of rotation, and liveness never depends on a database.
 *
 * <p>Details carry the database name only, never the URL, the user or the driver's error message.
 */
public final class DatabaseHealthIndicator implements HealthIndicator {

    /** The bean name under which services register the composite of every database. */
    public static final String CONTRIBUTOR_NAME = "kairosDatabases";

    private final Database database;
    private final int timeoutSeconds;

    /**
     * Creates an indicator.
     *
     * @param database the database to check
     * @param timeout how long the validation query may take; at least one second
     * @throws IllegalArgumentException if the timeout is shorter than a second
     */
    public DatabaseHealthIndicator(Database database, Duration timeout) {
        this.database = Objects.requireNonNull(database, "database");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("The health check timeout must be at least one second");
        }
        this.timeoutSeconds = Math.toIntExact(timeout.toSeconds());
    }

    /**
     * Builds the composite contributor of every database in a registry: {@code control} first, then the cells.
     *
     * @param registry the service's databases
     * @param timeout the timeout of each check
     * @return the composite contributor
     */
    public static CompositeHealthContributor forRegistry(DataSourceRegistry registry, Duration timeout) {
        Map<String, DatabaseHealthIndicator> indicators = new LinkedHashMap<>();
        for (Database database : registry.all()) {
            indicators.put(database.name(), new DatabaseHealthIndicator(database, timeout));
        }
        return CompositeHealthContributor.fromMap(indicators);
    }

    @Override
    public Health health() {
        try (Connection connection = database.dataSource().getConnection()) {
            boolean valid = connection.isValid(timeoutSeconds);
            return (valid ? Health.up() : Health.down())
                    .withDetail("database", database.name())
                    .build();
        } catch (SQLException e) {
            // The driver's message can contain host names or users; the cause stays out of the details.
            return Health.down().withDetail("database", database.name()).build();
        }
    }
}
