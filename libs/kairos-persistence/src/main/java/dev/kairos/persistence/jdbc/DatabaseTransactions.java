package dev.kairos.persistence.jdbc;

import dev.kairos.persistence.error.SqlErrorTranslator;
import dev.kairos.persistence.error.TransientRetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs units of work in transactions on one database.
 *
 * <p>Each call opens a transaction with the given {@link TransactionSettings}, hands the work the database's
 * {@link JdbcClient}, and commits when the work returns or rolls back when it throws. Failures are translated by
 * {@link SqlErrorTranslator}: a unique violation surfaces as a {@code ConflictException} and a serialization failure or
 * deadlock as a {@code RetryableDatabaseException}, which the {@code executeWithRetry} variants retry up to three
 * times.
 *
 * <p>One transaction never spans two databases (IMPLEMENTATION_CHECKLIST section 4.6). Opening a transaction while
 * the thread already holds one on another database throws {@link IllegalStateException}; a call made while a
 * transaction on the same database is open joins it, and its settings are ignored.
 *
 * <p>The duration of every transaction is recorded in {@code kairos_db_transaction_duration_seconds}, tagged with
 * the database name ({@code control}, {@code cell-0}, ...) as {@code cell}.
 *
 * <p>Thread-safe.
 */
public final class DatabaseTransactions {

    /** Meter name of the transaction timer; Prometheus shows it as {@code kairos_db_transaction_duration_seconds}. */
    public static final String TRANSACTION_TIMER = "kairos.db.transaction.duration";

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final DataSourceTransactionManager transactionManager;
    private final TransactionSettings defaults;
    private final TransientRetry retry;
    private final MeterRegistry meterRegistry;
    private final Timer timer;

    /**
     * Creates the transaction runner of one database.
     *
     * @param name the database name, used as the {@code cell} tag
     * @param dataSource the database's pool
     * @param jdbc a client on the same pool
     * @param defaults the settings of calls that pass none
     * @param retry the retry helper of the {@code executeWithRetry} variants
     * @param meterRegistry where the transaction timer is registered
     */
    public DatabaseTransactions(
            String name,
            DataSource dataSource,
            JdbcClient jdbc,
            TransactionSettings defaults,
            TransientRetry retry,
            MeterRegistry meterRegistry) {
        Objects.requireNonNull(name, "name");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.transactionManager = new DataSourceTransactionManager(dataSource);
        this.timer = Timer.builder(TRANSACTION_TIMER)
                .description("Time from opening to committing or rolling back a database transaction")
                .tag("cell", name)
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    /**
     * Returns the settings used by calls that pass none.
     *
     * @return the default settings
     */
    public TransactionSettings defaults() {
        return defaults;
    }

    /**
     * Runs the work in a transaction with the default settings.
     *
     * @param <T> the result type
     * @param work the unit of work
     * @return the work's result, after the commit
     * @throws dev.kairos.domain.error.ConflictException if a unique constraint was violated
     * @throws dev.kairos.persistence.error.RetryableDatabaseException on a serialization failure or deadlock
     * @throws IllegalStateException if the thread holds a transaction on another database
     */
    public <T extends @Nullable Object> T execute(TransactionWork<T> work) {
        return execute(defaults, work);
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
     * @throws IllegalStateException if the thread holds a transaction on another database
     */
    public <T extends @Nullable Object> T execute(TransactionSettings settings, TransactionWork<T> work) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(work, "work");
        if (TransactionSynchronizationManager.hasResource(dataSource)) {
            // Already inside a transaction on this database: join it, like propagation REQUIRED.
            return translated(settings, () -> work.execute(jdbc));
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A transaction on another database is open on this thread; "
                    + "one transaction never spans two databases");
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(settings.isolation().value());
        template.setTimeout(settings.timeoutSeconds());
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return translated(settings, () -> template.execute(status -> work.execute(jdbc)));
        } finally {
            sample.stop(timer);
        }
    }

    /**
     * Runs the work in a transaction with the default settings, retrying the whole transaction on a serialization
     * failure or deadlock (at most three attempts, with jitter).
     *
     * @param <T> the result type
     * @param work the unit of work; it may run more than once, so it must not have side effects outside the database
     * @return the result of the first attempt that commits
     * @throws dev.kairos.domain.error.ConflictException if a unique constraint was violated
     * @throws dev.kairos.persistence.error.RetryableDatabaseException if the last attempt failed with one
     * @throws IllegalStateException if the thread holds a transaction on another database
     */
    public <T extends @Nullable Object> T executeWithRetry(TransactionWork<T> work) {
        return executeWithRetry(defaults, work);
    }

    /**
     * Runs the work in a transaction with the given settings, retrying the whole transaction on a serialization
     * failure or deadlock (at most three attempts, with jitter).
     *
     * <p>Inside an open transaction on the same database there is nothing to retry on its own, so the work simply
     * joins that transaction and the outermost caller decides about retries.
     *
     * @param <T> the result type
     * @param settings isolation, deadline and conflicts of this transaction
     * @param work the unit of work; it may run more than once, so it must not have side effects outside the database
     * @return the result of the first attempt that commits
     * @throws dev.kairos.domain.error.ConflictException if a unique constraint was violated
     * @throws dev.kairos.persistence.error.RetryableDatabaseException if the last attempt failed with one
     * @throws IllegalStateException if the thread holds a transaction on another database
     */
    public <T extends @Nullable Object> T executeWithRetry(TransactionSettings settings, TransactionWork<T> work) {
        if (TransactionSynchronizationManager.hasResource(dataSource)) {
            return execute(settings, work);
        }
        return retry.call(() -> execute(settings, work));
    }

    private static <T extends @Nullable Object> T translated(TransactionSettings settings, Body<T> body) {
        try {
            return body.run();
        } catch (RuntimeException e) {
            throw SqlErrorTranslator.translate(e, settings.conflicts());
        }
    }

    @FunctionalInterface
    private interface Body<T extends @Nullable Object> {
        T run();
    }
}
