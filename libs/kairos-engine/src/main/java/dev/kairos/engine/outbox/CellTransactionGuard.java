package dev.kairos.engine.outbox;

import dev.kairos.domain.shard.Shard;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Checks that the current thread runs inside a transaction on the cell of a shard, as opened by
 * {@link dev.kairos.persistence.tx.CellTransactions}.
 *
 * <p>Writers whose rows must commit together with a state change (runs and their outbox rows) call it before every
 * write. Outside a transaction a statement would run in auto-commit mode and commit on its own, so a crash between two
 * statements could leave a queued run without its dispatch message, or a message without its run (HLD section 6). A
 * transaction that is open on another database, such as the control database or the other cell, does not count:
 * statements on this cell would not join it.
 *
 * <p>Stateless apart from the router and thread-safe.
 */
public final class CellTransactionGuard {

    private final CellRouter router;

    /**
     * Creates the guard.
     *
     * @param router routes shards to cells
     */
    public CellTransactionGuard(CellRouter router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    /**
     * Returns the cell of a shard, after checking that this thread has a transaction open on it.
     *
     * @param shard the shard the caller is about to write
     * @return the shard's cell; its statements join the open transaction
     * @throws IllegalStateException if no transaction is open on the shard's cell
     */
    public Cell requireTransaction(Shard shard) {
        Cell cell = router.forShard(Objects.requireNonNull(shard, "shard"));
        // DatabaseTransactions binds the cell's pool to the thread for the length of its transaction; statements on the
        // cell's client join that transaction only while the binding exists.
        if (!TransactionSynchronizationManager.hasResource(cell.database().dataSource())) {
            throw new IllegalStateException("Runs and outbox rows of shard " + shard.value()
                    + " must be written inside a CellTransactions callback on cell " + cell.id());
        }
        return cell;
    }
}
