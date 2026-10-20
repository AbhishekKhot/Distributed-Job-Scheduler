package dev.kairos.persistence.config;

import dev.kairos.persistence.error.ConstraintConflicts;
import dev.kairos.persistence.jdbc.ConnectionSettings;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.persistence.jdbc.TransactionSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.validation.annotation.Validated;

/**
 * Database configuration of a service, bound from {@code kairos.datasource.*}.
 *
 * <p>Connection settings come from the environment. A service maps its deployment variables onto these properties in
 * its {@code application.yml}, for example {@code kairos.datasource.control.url: ${KAIROS_CONTROL_DB_URL}} and
 * {@code kairos.datasource.cells[0].url: ${KAIROS_CELL_0_DB_URL}}, with the user name and password from
 * {@code KAIROS_DB_USERNAME} and {@code KAIROS_DB_PASSWORD}. Cell {@code n} of the list is the data source
 * {@code cell-n} of {@code cell_map}. Everything else has a default and is tuned per service.
 *
 * @param control the control database
 * @param cells the cell databases, in cell order
 * @param pool pool settings shared by every database
 * @param transaction transaction and statement defaults
 * @param cellMapRefresh how long a loaded {@code cell_map} is used before it is read again
 * @param healthTimeout the timeout of each database readiness check
 * @param fanOutTimeout the deadline of a list read across every cell
 */
@Validated
@ConfigurationProperties("kairos.datasource")
public record PersistenceProperties(
        @NotNull @Valid Connection control,
        @NotEmpty @Size(max = 64) List<@NotNull @Valid Connection> cells,
        @NotNull @Valid @DefaultValue Pool pool,
        @NotNull @Valid @DefaultValue Transaction transaction,
        @NotNull @DefaultValue("30s") Duration cellMapRefresh,
        @NotNull @DefaultValue("2s") Duration healthTimeout,
        @NotNull @DefaultValue("5s") Duration fanOutTimeout) {

    /**
     * Connection settings of one database.
     *
     * @param url a PostgreSQL JDBC URL; credentials never go in it
     * @param username the role to connect as ({@code sched_app} for services)
     * @param password the role's password; never logged
     * @param maximumPoolSize this database's pool size, or {@code null} for {@code pool.maximum-size}
     */
    public record Connection(
            @NotBlank @Size(max = 512) @Pattern(
                    regexp = "^jdbc:postgresql://[A-Za-z0-9.-]+(:[0-9]{1,5})?/[A-Za-z0-9_]+(\\?[A-Za-z0-9_.=&%/:-]*)?$")
            String url,

            @NotBlank @Size(max = 63) @Pattern(regexp = "^[a-z_][a-z0-9_]*$") String username,

            @NotBlank @Size(max = 1024) String password,

            @Nullable @Min(1) @Max(500) Integer maximumPoolSize) {

        /** Leaves the password out, so the settings can never end up in a log line. */
        @Override
        public String toString() {
            return "Connection[url=" + url + ", username=" + username + ", password=<redacted>, maximumPoolSize="
                    + maximumPoolSize + "]";
        }

        ConnectionSettings toSettings(int defaultPoolSize) {
            return new ConnectionSettings(
                    url, username, password, maximumPoolSize == null ? defaultPoolSize : maximumPoolSize);
        }
    }

    /**
     * Pool settings shared by every database.
     *
     * @param maximumSize the default pool size of each database
     * @param minimumIdle idle connections each pool keeps open
     * @param connectionTimeout how long a caller waits for a pooled connection
     * @param idleTimeout how long an idle connection above {@code minimumIdle} stays open
     * @param maxLifetime the age at which a connection is retired
     */
    public record Pool(
            @Min(1) @Max(500) @DefaultValue("10") int maximumSize,
            @Min(0) @Max(500) @DefaultValue("1") int minimumIdle,
            @NotNull @DefaultValue("3s") Duration connectionTimeout,
            @NotNull @DefaultValue("10m") Duration idleTimeout,
            @NotNull @DefaultValue("30m") Duration maxLifetime) {}

    /**
     * Transaction and statement defaults.
     *
     * @param isolation the default isolation level
     * @param timeout the default transaction deadline
     * @param statementTimeout the timeout of statements run outside a transaction
     * @param batchSize rows per JDBC batch
     */
    public record Transaction(
            @NotNull @DefaultValue("READ_COMMITTED") Isolation isolation,
            @NotNull @DefaultValue("10s") Duration timeout,
            @NotNull @DefaultValue("5s") Duration statementTimeout,
            @Min(1) @Max(10_000) @DefaultValue("500") int batchSize) {}

    /**
     * Returns the control database's connection settings.
     *
     * @return the settings
     */
    public ConnectionSettings controlSettings() {
        return control.toSettings(pool.maximumSize());
    }

    /**
     * Returns the cell databases' connection settings, in cell order.
     *
     * @return the settings
     */
    public List<ConnectionSettings> cellSettings() {
        return cells.stream().map(cell -> cell.toSettings(pool.maximumSize())).toList();
    }

    /**
     * Returns the options shared by every database.
     *
     * @return the options
     */
    public DatabaseOptions databaseOptions() {
        return new DatabaseOptions(
                pool.minimumIdle(),
                pool.connectionTimeout(),
                pool.idleTimeout(),
                pool.maxLifetime(),
                transaction.statementTimeout(),
                transaction.batchSize(),
                new TransactionSettings(transaction.isolation(), transaction.timeout(), ConstraintConflicts.none()));
    }
}
