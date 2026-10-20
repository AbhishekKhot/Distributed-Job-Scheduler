package dev.kairos.persistence.jdbc;

import dev.kairos.persistence.error.ConstraintConflicts;
import java.time.Duration;
import java.util.Objects;
import org.springframework.transaction.annotation.Isolation;

/**
 * How one transaction runs: its isolation level, its deadline and the conflicts its unique violations report.
 *
 * <p>The timeout is a deadline for the whole transaction. Every statement inside it gets the time that is left as its
 * JDBC query timeout, so a slow statement is cancelled by the driver instead of holding row locks and a pooled
 * connection indefinitely. The deadline is enforced in the client, which also works behind PgBouncer in transaction
 * mode, where per-session {@code SET statement_timeout} would leak between clients.
 *
 * @param isolation the isolation level; never {@link Isolation#DEFAULT}, so the level never depends on the server
 *     configuration
 * @param timeout the transaction deadline, at least one second (JDBC timeouts have a resolution of one second)
 * @param conflicts the conflicts reported for unique violations
 */
public record TransactionSettings(Isolation isolation, Duration timeout, ConstraintConflicts conflicts) {

    /**
     * The default isolation level. Hot-path statements rely on row locks ({@code FOR UPDATE SKIP LOCKED}) and fenced
     * conditional updates, not on snapshot isolation.
     */
    public static final Isolation DEFAULT_ISOLATION = Isolation.READ_COMMITTED;

    /** The default transaction deadline. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);

    /**
     * Validates the settings.
     *
     * @throws IllegalArgumentException if the isolation is {@link Isolation#DEFAULT} or the timeout is shorter than a
     *     second
     */
    public TransactionSettings {
        Objects.requireNonNull(isolation, "isolation");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(conflicts, "conflicts");
        if (isolation == Isolation.DEFAULT) {
            throw new IllegalArgumentException("Choose an explicit isolation level");
        }
        if (timeout.compareTo(MIN_TIMEOUT) < 0) {
            throw new IllegalArgumentException("The transaction timeout must be at least one second");
        }
    }

    /**
     * Returns the defaults: read committed, a 10-second deadline and no registered constraints.
     *
     * @return the default settings
     */
    public static TransactionSettings defaults() {
        return new TransactionSettings(DEFAULT_ISOLATION, DEFAULT_TIMEOUT, ConstraintConflicts.none());
    }

    /**
     * Returns a copy with another isolation level.
     *
     * @param newIsolation the isolation level
     * @return the new settings
     */
    public TransactionSettings withIsolation(Isolation newIsolation) {
        return new TransactionSettings(newIsolation, timeout, conflicts);
    }

    /**
     * Returns a copy with another deadline.
     *
     * @param newTimeout the deadline, at least one second
     * @return the new settings
     */
    public TransactionSettings withTimeout(Duration newTimeout) {
        return new TransactionSettings(isolation, newTimeout, conflicts);
    }

    /**
     * Returns a copy that reports other conflicts.
     *
     * @param newConflicts the conflicts for unique violations
     * @return the new settings
     */
    public TransactionSettings withConflicts(ConstraintConflicts newConflicts) {
        return new TransactionSettings(isolation, timeout, newConflicts);
    }

    /**
     * Returns the deadline in whole seconds, rounded up, as JDBC and Spring expect it.
     *
     * @return the timeout in seconds
     */
    public int timeoutSeconds() {
        return Math.toIntExact(ceilSeconds(timeout));
    }

    static long ceilSeconds(Duration duration) {
        long seconds = duration.getSeconds();
        return duration.getNano() == 0 ? seconds : seconds + 1;
    }
}
