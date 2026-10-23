package dev.kairos.watcher.lease;

import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.cell.CellStatus;
import dev.kairos.watcher.node.NodeRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Renews, acquires and releases generation-fenced shard leases in each writable cell. */
public final class ShardLeaseManager implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ShardLeaseManager.class);
    private static final String ENSURE_ROWS = """
            INSERT INTO scheduler.shard_leases (shard_id)
            SELECT value::smallint FROM generate_series(:fromShard, :toShard) AS shards(value)
            ON CONFLICT (shard_id) DO NOTHING
            """;
    private static final String OWNED = """
            SELECT shard_id, generation, lease_until FROM scheduler.shard_leases
             WHERE shard_id BETWEEN :fromShard AND :toShard AND owner_node = :node
               AND lease_until > clock_timestamp()
             ORDER BY lease_until, shard_id
            """;
    private static final String RENEW = """
            UPDATE scheduler.shard_leases
               SET lease_until = clock_timestamp() + (:ttlMs * interval '1 millisecond')
             WHERE shard_id = :shard AND owner_node = :node AND generation = :generation
               AND lease_until > clock_timestamp()
            RETURNING lease_until
            """;
    private static final String ACQUIRE = """
            WITH candidates AS (
                SELECT shard_id FROM scheduler.shard_leases
                 WHERE shard_id BETWEEN :fromShard AND :toShard
                   AND (owner_node IS NULL OR lease_until <= clock_timestamp())
                 ORDER BY shard_id FOR UPDATE SKIP LOCKED LIMIT :limit
            )
            UPDATE scheduler.shard_leases AS leases
               SET owner_node = :node, generation = leases.generation + 1,
                   lease_until = clock_timestamp() + (:ttlMs * interval '1 millisecond')
              FROM candidates
             WHERE leases.shard_id = candidates.shard_id
            RETURNING leases.shard_id, leases.generation, leases.lease_until
            """;
    private static final String RELEASE_ONE = """
            UPDATE scheduler.shard_leases
               SET owner_node = NULL, lease_until = NULL, generation = generation + 1
             WHERE shard_id = :shard AND owner_node = :node AND generation = :generation
               AND lease_until > clock_timestamp()
            """;
    private static final String RELEASE_ALL = """
            UPDATE scheduler.shard_leases
               SET owner_node = NULL, lease_until = NULL, generation = generation + 1
             WHERE owner_node = :node
            """;

    private final CellRouter cells;
    private final NodeRegistry nodes;
    private final OwnedShards owned;
    private final Duration leaseTtl;
    private final Duration safetyMargin;
    private final int surplus;
    private final MeterRegistry meters;
    private boolean closed;

    /**
     * Creates a manager.
     * @param cells routed writable cell databases
     * @param nodes registry of live watcher nodes
     * @param owned local lease view
     * @param leaseTtl database lease duration
     * @param safetyMargin time before lease expiry when work must stop
     * @param surplus extra leases retained to reduce churn
     * @param meters metrics registry
     */
    public ShardLeaseManager(
            CellRouter cells,
            NodeRegistry nodes,
            OwnedShards owned,
            Duration leaseTtl,
            Duration safetyMargin,
            int surplus,
            MeterRegistry meters) {
        this.cells = Objects.requireNonNull(cells, "cells");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.owned = Objects.requireNonNull(owned, "owned");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
        this.safetyMargin = Objects.requireNonNull(safetyMargin, "safetyMargin");
        this.meters = Objects.requireNonNull(meters, "meters");
        if (leaseTtl.isNegative()
                || leaseTtl.isZero()
                || safetyMargin.isNegative()
                || safetyMargin.compareTo(leaseTtl) >= 0
                || surplus < 0) {
            throw new IllegalArgumentException("Invalid lease settings");
        }
        this.surplus = surplus;
        for (Cell cell : cells.allCells()) {
            Gauge.builder("kairos_shard_leases_owned", owned, view -> view.count(cell.id()))
                    .tag("cell", Integer.toString(cell.id()))
                    .register(meters);
        }
    }

    /** Registers this node, removes stale registrations and reconciles all cells. */
    public synchronized void reconcile() {
        if (closed) return;
        int watcherCount = nodes.heartbeatAndCountWatchers();
        for (Cell cell : cells.allCells()) {
            if (cell.status() == CellStatus.READ_ONLY) {
                owned.remove(cell.id());
                continue;
            }
            try {
                reconcile(cell, watcherCount);
            } catch (RuntimeException failure) {
                LOG.warn(
                        "Shard lease reconciliation failed for cell {}: {}",
                        cell.id(),
                        failure.getClass().getSimpleName());
            }
        }
    }

    private void reconcile(Cell cell, int watcherCount) {
        int from = cell.range().shardFrom();
        int to = cell.range().shardTo();
        JdbcClient jdbc = cell.jdbc();
        jdbc.sql(ENSURE_ROWS).param("fromShard", from).param("toShard", to).update();
        String node = nodes.identity().nodeId();
        List<LeaseRow> current = jdbc.sql(OWNED)
                .param("fromShard", from)
                .param("toShard", to)
                .param("node", node)
                .query((row, index) -> new LeaseRow(
                        row.getInt("shard_id"),
                        row.getLong("generation"),
                        row.getObject("lease_until", OffsetDateTime.class).toInstant()))
                .list();
        int shardCount = to - from + 1;
        int desired = (shardCount + watcherCount - 1) / watcherCount;
        int releaseCount = Math.max(0, current.size() - desired - surplus);
        List<LeaseRow> remaining = new ArrayList<>();
        for (int index = 0; index < current.size(); index++) {
            LeaseRow lease = current.get(index);
            if (index < releaseCount) {
                int changed = jdbc.sql(RELEASE_ONE)
                        .param("shard", lease.shard())
                        .param("node", node)
                        .param("generation", lease.generation())
                        .update();
                if (changed > 0) changed("released", cell.id());
            } else {
                remaining.add(lease);
            }
        }
        List<OwnedShards.Lease> snapshot = new ArrayList<>();
        for (LeaseRow lease : remaining) {
            var renewed = jdbc.sql(RENEW)
                    .param("shard", lease.shard())
                    .param("node", node)
                    .param("generation", lease.generation())
                    .param("ttlMs", leaseTtl.toMillis())
                    .query((row, index) ->
                            row.getObject("lease_until", OffsetDateTime.class).toInstant())
                    .optional();
            if (renewed.isPresent()) {
                Instant until = renewed.get();
                snapshot.add(
                        new OwnedShards.Lease(lease.shard(), lease.generation(), until, until.minus(safetyMargin)));
            } else {
                changed("lost", cell.id());
            }
        }
        int limit = Math.max(0, desired + surplus - snapshot.size());
        if (limit > 0) {
            List<LeaseRow> acquired = jdbc.sql(ACQUIRE)
                    .param("fromShard", from)
                    .param("toShard", to)
                    .param("node", node)
                    .param("ttlMs", leaseTtl.toMillis())
                    .param("limit", limit)
                    .query((row, index) -> new LeaseRow(
                            row.getInt("shard_id"),
                            row.getLong("generation"),
                            row.getObject("lease_until", OffsetDateTime.class).toInstant()))
                    .list();
            for (LeaseRow lease : acquired) {
                snapshot.add(new OwnedShards.Lease(
                        lease.shard(),
                        lease.generation(),
                        lease.leaseUntil(),
                        lease.leaseUntil().minus(safetyMargin)));
                changed("acquired", cell.id());
            }
        }
        owned.replace(cell.id(), snapshot);
    }

    private void releaseAll(Cell cell) {
        int released = cell.jdbc()
                .sql(RELEASE_ALL)
                .param("node", nodes.identity().nodeId())
                .update();
        for (int i = 0; i < released; i++) changed("released", cell.id());
        owned.remove(cell.id());
    }

    private void changed(String outcome, int cell) {
        Counter.builder("kairos_shard_lease_changes_total")
                .tag("outcome", outcome)
                .tag("cell", Integer.toString(cell))
                .register(meters)
                .increment();
    }

    /** Releases leases on shutdown so a replacement watcher can take over immediately. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Cell cell : cells.allCells()) {
            if (cell.status() == CellStatus.READ_ONLY) owned.remove(cell.id());
            else releaseAll(cell);
        }
        nodes.close();
    }

    private record LeaseRow(int shard, long generation, Instant leaseUntil) {}
}
