package dev.kairos.api.team.application;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Runs a unit of work in one control-database transaction, so a change, its keysets, the session revocations and the
 * audit entries commit together. The ports used inside the work join the transaction.
 */
public interface TeamTransactions {

    /**
     * Runs work in a transaction.
     *
     * @param <T> the result type
     * @param work the work
     * @return the work's result, after the commit
     */
    <T extends @Nullable Object> T inTransaction(Supplier<T> work);
}
