package dev.kairos.watcher.fire;

import dev.kairos.engine.run.FireNotifier;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.watcher.lease.OwnedShards;
import dev.kairos.watcher.node.NodeIdentity;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Listens for committed shard notifications and wakes the fire path under the same lease guard. */
public final class FireNotificationListener implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FireNotificationListener.class);
    private static final String GUARD = """
            SELECT generation FROM scheduler.shard_leases
             WHERE shard_id = :shard AND owner_node = :node AND generation = :generation
               AND lease_until > clock_timestamp()
             FOR SHARE
            """;

    private final CellRouter cells;
    private final OwnedShards owned;
    private final NodeIdentity identity;
    private final Clock clock;
    private final FirePoller poller;
    private final ExecutorService listeners = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;

    /** Begins one PostgreSQL listener per configured writable cell. */
    public FireNotificationListener(
            CellRouter cells, OwnedShards owned, NodeIdentity identity, Clock clock, FirePoller poller) {
        this.cells = cells;
        this.owned = owned;
        this.identity = identity;
        this.clock = clock;
        this.poller = poller;
        for (Cell cell : cells.allCells()) {
            if (cell.status() != dev.kairos.persistence.cell.CellStatus.READ_ONLY) {
                listeners.submit(() -> listen(cell));
            }
        }
    }

    private void listen(Cell cell) {
        String channel = FireNotifier.channel(cell.id());
        while (!closed) {
            try (Connection connection = cell.database().dataSource().getConnection()) {
                try (var statement = connection.createStatement()) {
                    statement.execute("LISTEN " + channel);
                }
                PGConnection pg = connection.unwrap(PGConnection.class);
                while (!closed) {
                    PGNotification[] messages = pg.getNotifications(1_000);
                    if (messages != null) {
                        for (PGNotification message : messages) {
                            try {
                                int shard = Integer.parseInt(message.getParameter());
                                wake(cell, shard);
                            } catch (NumberFormatException ignored) {
                                LOG.warn("Ignoring invalid fire notification payload for cell {}", cell.id());
                            } catch (RuntimeException failure) {
                                LOG.warn(
                                        "Fire notification failed for cell {}: {}",
                                        cell.id(),
                                        failure.getClass().getSimpleName());
                            }
                        }
                    }
                }
            } catch (SQLException failure) {
                if (!closed) LOG.warn("Fire notification listener disconnected from cell {}", cell.id());
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void wake(Cell cell, int shard) {
        List<OwnedShards.Lease> candidates = new ArrayList<>(owned.inCell(cell.id()));
        for (OwnedShards.Lease lease : candidates) {
            if (lease.shard() != shard || !owned.mayStart(cell.id(), shard, lease.generation(), clock.instant())) {
                continue;
            }
            cell.transactions().execute(jdbc -> {
                boolean valid = jdbc.sql(GUARD)
                        .param("shard", shard)
                        .param("node", identity.nodeId())
                        .param("generation", lease.generation())
                        .query(Long.class)
                        .optional()
                        .isPresent();
                if (valid && owned.mayStart(cell.id(), shard, lease.generation(), clock.instant())) {
                    poller.poll(cell, shard);
                }
                return null;
            });
            return;
        }
    }

    @Override
    public void close() {
        closed = true;
        listeners.shutdownNow();
        try {
            listeners.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
