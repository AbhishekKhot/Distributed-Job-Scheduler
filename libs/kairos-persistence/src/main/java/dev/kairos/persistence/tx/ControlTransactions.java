package dev.kairos.persistence.tx;

import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.TransactionSettings;
import dev.kairos.persistence.jdbc.TransactionWork;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Runs a unit of work in one transaction on the control database (teams, users, credentials, endpoints, cell map).
 *
 * <p>Failures are translated as described in {@link dev.kairos.persistence.jdbc.DatabaseTransactions}. Opening a
 * cell transaction from inside the work, or this one from inside a cell transaction, is rejected: a step that touches
 * both databases is split into two transactions, ordered so that a crash between them leaves a state a sweeper or a
 * retry can finish. Thread-safe.
 */
public final class ControlTransactions {

    private final Database control;

    /**
     * Creates the control transaction runner.
     *
     * @param control the control database
     * @throws IllegalArgumentException if the database is not the control database
     */
    public ControlTransactions(Database control) {
        Objects.requireNonNull(control, "control");
        if (!Database.CONTROL.equals(control.name())) {
            throw new IllegalArgumentException("Not the control database: " + control.name());
        }
        this.control = control;
    }

    /**
     * Runs the work in a transaction with the default settings.
     *
     * @param <T> the result type
     * @param work the unit of work
     * @return the work's result, after the commit
     * @throws dev.kairos.domain.error.ConflictException if a unique constraint was violated
     * @throws dev.kairos.persistence.error.RetryableDatabaseException on a serialization failure or deadlock
     */
    public <T extends @Nullable Object> T execute(TransactionWork<T> work) {
        return control.transactions().execute(work);
    }

    /**
     * Runs the work in a transaction with the given settings.
     *
     * @param <T> the result type
     * @param settings isolation, deadline and conflicts of this transaction
     * @param work the unit of work
     * @return the work's result, after the commit
     * @throws dev.kairos.domain.error.ConflictException if a unique constraint was violated
     * @throws dev.kairos.persistence.error.RetryableDatabaseException on a serialization failure or deadlock
     */
    public <T extends @Nullable Object> T execute(TransactionSettings settings, TransactionWork<T> work) {
        return control.transactions().execute(settings, work);
    }

    /**
     * Runs the work in a transaction with the default settings, retrying the whole transaction on a serialization
     * failure or deadlock.
     *
     * @param <T> the result type
     * @param work the unit of work; it may run more than once
     * @return the result of the first attempt that commits
     * @throws dev.kairos.domain.error.ConflictException if a unique constraint was violated
     * @throws dev.kairos.persistence.error.RetryableDatabaseException if the last attempt failed with one
     */
    public <T extends @Nullable Object> T executeWithRetry(TransactionWork<T> work) {
        return control.transactions().executeWithRetry(work);
    }

    /**
     * Runs the work in a transaction with the given settings, retrying the whole transaction on a serialization
     * failure or deadlock.
     *
     * @param <T> the result type
     * @param settings isolation, deadline and conflicts of this transaction
     * @param work the unit of work; it may run more than once
     * @return the result of the first attempt that commits
     * @throws dev.kairos.domain.error.ConflictException if a unique constraint was violated
     * @throws dev.kairos.persistence.error.RetryableDatabaseException if the last attempt failed with one
     */
    public <T extends @Nullable Object> T executeWithRetry(TransactionSettings settings, TransactionWork<T> work) {
        return control.transactions().executeWithRetry(settings, work);
    }
}
