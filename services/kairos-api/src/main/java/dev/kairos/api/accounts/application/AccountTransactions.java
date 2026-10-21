package dev.kairos.api.accounts.application;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Runs a unit of work in one control-database transaction, so a change and its audit entry commit together. The
 * ports used inside the work join the transaction.
 */
public interface AccountTransactions {

    /**
     * Runs work in a transaction.
     *
     * @param <T> the result type
     * @param work the work
     * @return the work's result, after the commit
     */
    <T extends @Nullable Object> T inTransaction(Supplier<T> work);
}
