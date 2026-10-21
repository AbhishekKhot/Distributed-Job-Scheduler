package dev.kairos.api.accounts.infrastructure;

import dev.kairos.api.accounts.application.AccountTransactions;
import dev.kairos.persistence.tx.ControlTransactions;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * {@link AccountTransactions} on the control database. The control {@code JdbcClient} used by the accounts adapters
 * and the refresh-session store shares the transaction's connection, so every statement of the work commits or rolls
 * back together. Thread-safe.
 */
public final class ControlAccountTransactions implements AccountTransactions {

    private final ControlTransactions transactions;

    /**
     * Creates the unit of work.
     *
     * @param transactions control-database transactions
     */
    public ControlAccountTransactions(ControlTransactions transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public <T extends @Nullable Object> T inTransaction(Supplier<T> work) {
        return transactions.execute(jdbc -> work.get());
    }
}
