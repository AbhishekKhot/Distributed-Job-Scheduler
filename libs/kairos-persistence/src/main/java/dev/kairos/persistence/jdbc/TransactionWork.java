package dev.kairos.persistence.jdbc;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A unit of work that runs inside one database transaction.
 *
 * <p>The work uses the {@link JdbcClient} it is given; every statement it runs through that client joins the
 * transaction. It must not open a transaction on another database (that is rejected) and must not catch and swallow
 * database exceptions, or a failed transaction could commit half its work.
 *
 * @param <T> the result type
 */
@FunctionalInterface
public interface TransactionWork<T extends @Nullable Object> {

    /**
     * Runs the work.
     *
     * @param jdbc the client of the database the transaction is open on
     * @return the result
     */
    T execute(JdbcClient jdbc);
}
