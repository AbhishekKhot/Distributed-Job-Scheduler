package dev.kairos.persistence.jdbc;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings shared by every database of a service: pool behaviour, transaction defaults, the timeout of statements run
 * outside a transaction, and the batch size of {@link Database#batchUpdate}.
 *
 * @param minimumIdle idle connections each pool keeps open
 * @param connectionTimeout how long a caller waits for a pooled connection before failing
 * @param idleTimeout how long an idle connection above {@code minimumIdle} stays open
 * @param maxLifetime the age at which a connection is retired, kept below any proxy or server idle cut-off
 * @param statementTimeout the JDBC query timeout of statements run outside a transaction; inside one, the transaction
 *     deadline applies instead
 * @param batchSize the number of rows sent per JDBC batch
 * @param transaction the defaults of {@link DatabaseTransactions}
 */
public record DatabaseOptions(
        int minimumIdle,
        Duration connectionTimeout,
        Duration idleTimeout,
        Duration maxLifetime,
        Duration statementTimeout,
        int batchSize,
        TransactionSettings transaction) {

    /**
     * Validates the options.
     *
     * @throws IllegalArgumentException if a count is out of range or a duration is not positive
     */
    public DatabaseOptions {
        Objects.requireNonNull(transaction, "transaction");
        if (minimumIdle < 0) {
            throw new IllegalArgumentException("minimumIdle must not be negative");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        requirePositive(connectionTimeout, "connectionTimeout");
        requirePositive(idleTimeout, "idleTimeout");
        requirePositive(maxLifetime, "maxLifetime");
        requirePositive(statementTimeout, "statementTimeout");
    }

    /**
     * Returns the defaults: 1 idle connection, 3s to get a connection, 10 minutes idle, 30 minutes lifetime, 5s per
     * statement, batches of 500 rows and {@link TransactionSettings#defaults()}.
     *
     * @return the default options
     */
    public static DatabaseOptions defaults() {
        return new DatabaseOptions(
                1,
                Duration.ofSeconds(3),
                Duration.ofMinutes(10),
                Duration.ofMinutes(30),
                Duration.ofSeconds(5),
                500,
                TransactionSettings.defaults());
    }

    /**
     * Returns the statement timeout in whole seconds, rounded up, as JDBC expects it.
     *
     * @return the timeout in seconds
     */
    public int statementTimeoutSeconds() {
        return Math.toIntExact(TransactionSettings.ceilSeconds(statementTimeout));
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
