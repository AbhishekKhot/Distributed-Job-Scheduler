package dev.kairos.api.team.infrastructure;

import dev.kairos.api.team.application.TeamTransactions;
import dev.kairos.persistence.tx.ControlTransactions;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * {@link TeamTransactions} on the control database. The control {@code JdbcClient} used by the team adapters and the
 * refresh-session store shares the transaction's connection, so every statement of the work commits or rolls back
 * together. Thread-safe.
 */
public final class ControlTeamTransactions implements TeamTransactions {

    private final ControlTransactions transactions;

    /**
     * Creates the unit of work.
     *
     * @param transactions control-database transactions
     */
    public ControlTeamTransactions(ControlTransactions transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public <T extends @Nullable Object> T inTransaction(Supplier<T> work) {
        return transactions.execute(jdbc -> work.get());
    }
}
