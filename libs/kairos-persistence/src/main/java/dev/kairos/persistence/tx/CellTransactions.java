package dev.kairos.persistence.tx;

import dev.kairos.domain.shard.Shard;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.jdbc.TransactionSettings;
import dev.kairos.persistence.jdbc.TransactionWork;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Runs a unit of work in one transaction on the cell that holds a shard.
 *
 * <p>The shard is routed through {@link CellRouter}, and the work gets that cell's {@code JdbcClient}. Callers with an
 * id pass {@code id.shard()} (or {@code KairosId.shardOf(uuid)}). Because a transaction is bound to the cell of one
 * shard, a unit of work can only touch rows of that cell; one transaction never spans two databases, and opening a
 * second transaction on another cell from inside the work is rejected.
 *
 * <p>Failures are translated as described in {@link dev.kairos.persistence.jdbc.DatabaseTransactions}. Thread-safe.
 */
public final class CellTransactions {

    private final CellRouter router;

    /**
     * Creates the cell transaction runner.
     *
     * @param router routes shards to cells
     */
    public CellTransactions(CellRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    /**
     * Runs the work in a transaction with the default settings on the shard's cell.
     *
     * @param <T> the result type
     * @param shard the shard whose cell runs the transaction
     * @param work the unit of work
     * @return the work's result, after the commit
     * @throws dev.kairos.domain.error.ConflictException if a unique constraint was violated
     * @throws dev.kairos.persistence.error.RetryableDatabaseException on a serialization failure or deadlock
     */
    public <T extends @Nullable Object> T execute(Shard shard, TransactionWork<T> work) {
        return router.forShard(shard).transactions().execute(work);
    }

    /**
     * Runs the work in a transaction with the given settings on the shard's cell.
     *
     * @param <T> the result type
     * @param shard the shard whose cell runs the transaction
     * @param settings isolation, deadline and conflicts of this transaction
     * @param work the unit of work
     * @return the work's result, after the commit
     * @throws dev.kairos.domain.error.ConflictException if a unique constraint was violated
     * @throws dev.kairos.persistence.error.RetryableDatabaseException on a serialization failure or deadlock
     */
    public <T extends @Nullable Object> T execute(Shard shard, TransactionSettings settings, TransactionWork<T> work) {
        return router.forShard(shard).transactions().execute(settings, work);
    }

    /**
     * Runs the work in a transaction with the default settings on the shard's cell, retrying the whole transaction on
     * a serialization failure or deadlock.
     *
     * @param <T> the result type
     * @param shard the shard whose cell runs the transaction
     * @param work the unit of work; it may run more than once
     * @return the result of the first attempt that commits
     * @throws dev.kairos.domain.error.ConflictException if a unique constraint was violated
     * @throws dev.kairos.persistence.error.RetryableDatabaseException if the last attempt failed with one
     */
    public <T extends @Nullable Object> T executeWithRetry(Shard shard, TransactionWork<T> work) {
        return router.forShard(shard).transactions().executeWithRetry(work);
    }

    /**
     * Runs the work in a transaction with the given settings on the shard's cell, retrying the whole transaction on a
     * serialization failure or deadlock.
     *
     * @param <T> the result type
     * @param shard the shard whose cell runs the transaction
     * @param settings isolation, deadline and conflicts of this transaction
     * @param work the unit of work; it may run more than once
     * @return the result of the first attempt that commits
     * @throws dev.kairos.domain.error.ConflictException if a unique constraint was violated
     * @throws dev.kairos.persistence.error.RetryableDatabaseException if the last attempt failed with one
     */
    public <T extends @Nullable Object> T executeWithRetry(
            Shard shard, TransactionSettings settings, TransactionWork<T> work) {
        return router.forShard(shard).transactions().executeWithRetry(settings, work);
    }
}
