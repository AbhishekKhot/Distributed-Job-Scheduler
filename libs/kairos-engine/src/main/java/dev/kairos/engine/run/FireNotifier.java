package dev.kairos.engine.run;

import dev.kairos.domain.shard.Shard;
import dev.kairos.engine.outbox.CellTransactionGuard;
import dev.kairos.persistence.cell.Cell;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Wakes the watcher of a cell when runs are queued outside its own poll cycle, with PostgreSQL
 * {@code NOTIFY kairos_fire_<cell>}.
 *
 * <p>The payload is the shard number, so a listener can poll just that shard. Notifications are transactional:
 * PostgreSQL delivers them when the caller's transaction commits and drops them when it rolls back, so a listener
 * never sees a run that does not exist; identical notifications of one transaction are delivered once. A lost
 * notification costs only latency, because the pollers still run on their own schedule (LLD section 4.1).
 *
 * <p>Must be called inside a {@link dev.kairos.persistence.tx.CellTransactions} callback on the shards' cell. The
 * channel name is built from the cell number only and the payload is bound as a parameter of {@code pg_notify}.
 *
 * <p>Thread-safe.
 */
public final class FireNotifier {

    /** Prefix of the channel of each cell; the cell number follows. */
    public static final String CHANNEL_PREFIX = "kairos_fire_";

    private static final String NOTIFY = "SELECT pg_notify(:channel, :payload)";

    private final CellTransactionGuard guard;

    /**
     * Creates the notifier.
     *
     * @param guard checks that a transaction is open on the shards' cell
     */
    public FireNotifier(CellTransactionGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    /**
     * Returns the channel a cell's watchers listen on.
     *
     * @param cellId the cell number, not negative
     * @return for example {@code kairos_fire_0}
     * @throws IllegalArgumentException if the cell number is negative
     */
    public static String channel(int cellId) {
        if (cellId < 0) {
            throw new IllegalArgumentException("The cell number must not be negative");
        }
        return CHANNEL_PREFIX + cellId;
    }

    /**
     * Notifies the cell's channel once per shard, delivered when the open transaction commits.
     *
     * @param shards the shards that got new queued runs; may be empty
     * @throws IllegalStateException if a shard's cell has no open transaction on this thread
     */
    public void notifyShards(Set<Shard> shards) {
        Objects.requireNonNull(shards, "shards");
        for (Shard shard : new TreeSet<>(shards)) {
            Cell cell = guard.requireTransaction(shard);
            cell.jdbc()
                    .sql(NOTIFY)
                    .param("channel", channel(cell.id()))
                    .param("payload", Integer.toString(shard.value()))
                    .query((resultSet, rowNumber) -> Boolean.TRUE)
                    .list();
        }
    }
}
