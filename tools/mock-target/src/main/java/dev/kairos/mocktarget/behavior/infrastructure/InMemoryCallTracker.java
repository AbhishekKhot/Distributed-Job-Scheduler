package dev.kairos.mocktarget.behavior.infrastructure;

import dev.kairos.mocktarget.behavior.application.CallTracker;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Never evicts keys silently. Snapshot copies briefly exclude writers; serialization happens after release. */
public final class InMemoryCallTracker implements CallTracker {
    private final ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<>();
    private final LongAdder calls = new LongAdder(),
            duplicates = new LongAdder(),
            duplicateKeys = new LongAdder(),
            unkeyed = new LongAdder(),
            signatures = new LongAdder(),
            rejected = new LongAdder(),
            snapshotFailures = new LongAdder();
    private final AtomicLong slots = new AtomicLong();
    private final ReentrantReadWriteLock barrier = new ReentrantReadWriteLock();
    private final int maxKeys;
    private final Clock clock;
    private final String instanceId = UUID.randomUUID().toString();
    private final Instant startedAt;

    public InMemoryCallTracker(int maxKeys, Clock clock) {
        if (maxKeys < 1) throw new IllegalArgumentException("Key capacity must be positive.");
        this.maxKeys = maxKeys;
        this.clock = clock;
        startedAt = clock.instant();
    }

    @Override
    public boolean record(String key, boolean signatureValid) {
        barrier.readLock().lock();
        try {
            calls.increment();
            if (!signatureValid) signatures.increment();
            if (key.isEmpty()) {
                unkeyed.increment();
                return true;
            }
            AtomicLong counter = counts.computeIfAbsent(key, ignored -> {
                if (slots.incrementAndGet() > maxKeys) {
                    slots.decrementAndGet();
                    return null;
                }
                return new AtomicLong();
            });
            if (counter == null) {
                rejected.increment();
                return false;
            }
            long count = counter.incrementAndGet();
            if (count > 1) duplicates.increment();
            if (count == 2) duplicateKeys.increment();
            return true;
        } finally {
            barrier.readLock().unlock();
        }
    }

    @Override
    public Stats stats() {
        barrier.writeLock().lock();
        try {
            return current();
        } finally {
            barrier.writeLock().unlock();
        }
    }

    private Stats current() {
        return new Stats(
                instanceId,
                startedAt,
                calls.sum(),
                counts.size(),
                duplicateKeys.sum(),
                duplicates.sum(),
                unkeyed.sum(),
                signatures.sum(),
                rejected.sum(),
                rejected.sum() == 0 && unkeyed.sum() == 0,
                snapshotFailures.sum());
    }

    @Override
    public Snapshot snapshot() {
        barrier.writeLock().lock();
        try {
            Map<String, Long> copy = new HashMap<>();
            counts.forEach((key, count) -> copy.put(key, count.get()));
            return new Snapshot(1, clock.instant(), current(), Map.copyOf(copy));
        } finally {
            barrier.writeLock().unlock();
        }
    }

    @Override
    public void snapshotFailed() {
        snapshotFailures.increment();
    }
}
