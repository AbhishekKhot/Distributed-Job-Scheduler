package dev.kairos.watcher.lease;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe, immutable snapshots of the shard leases currently held by this process. */
public final class OwnedShards {
    private final ConcurrentMap<Integer, Map<Integer, Lease>> byCell = new ConcurrentHashMap<>();

    /** A lease token and local stop-work deadline. */
    public record Lease(int shard, long generation, Instant leaseUntil, Instant workDeadline) {
        /** Checks the lease token. */
        public Lease {
            Objects.requireNonNull(leaseUntil, "leaseUntil");
            Objects.requireNonNull(workDeadline, "workDeadline");
            if (shard < 0 || shard > 255 || generation < 0 || !workDeadline.isBefore(leaseUntil))
                throw new IllegalArgumentException("Invalid shard lease");
        }
    }

    /** Returns a sorted snapshot for one cell. */
    public List<Lease> inCell(int cellId) {
        Map<Integer, Lease> snapshot = byCell.getOrDefault(cellId, Map.of());
        return snapshot.values().stream()
                .sorted(Comparator.comparingInt(Lease::shard))
                .toList();
    }

    /** Returns whether this snapshot permits starting work on a shard at the supplied time. */
    public boolean mayStart(int cellId, int shard, long generation, Instant now) {
        Lease lease = byCell.getOrDefault(cellId, Map.of()).get(shard);
        return lease != null && lease.generation() == generation && now.isBefore(lease.workDeadline());
    }

    /** Returns a count for metrics and health reporting. */
    public int count(int cellId) {
        return byCell.getOrDefault(cellId, Map.of()).size();
    }

    void replace(int cellId, List<Lease> leases) {
        Map<Integer, Lease> next = new ConcurrentHashMap<>();
        leases.forEach(lease -> next.put(lease.shard(), lease));
        byCell.put(cellId, Map.copyOf(next));
    }

    void remove(int cellId) {
        byCell.remove(cellId);
    }

    /** Returns all owned leases as an immutable cell-sorted snapshot. */
    public Map<Integer, List<Lease>> snapshot() {
        Map<Integer, List<Lease>> result = new java.util.TreeMap<>();
        byCell.forEach((cell, leases) -> {
            List<Lease> sorted = new ArrayList<>(leases.values());
            sorted.sort(Comparator.comparingInt(Lease::shard));
            result.put(cell, List.copyOf(sorted));
        });
        return Map.copyOf(result);
    }
}
