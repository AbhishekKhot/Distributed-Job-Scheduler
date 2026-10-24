package dev.kairos.executor.consume.infrastructure;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/** Tracks completed records in poll order and exposes only each partition's highest contiguous safe offset. */
public final class DispatchOffsetTracker {

    private static final AtomicLong REGISTRATIONS = new AtomicLong();

    private final Map<TopicPartition, ArrayDeque<Entry>> pending = new HashMap<>();

    /** Registers records before any worker is started. */
    public long register(TopicPartition partition, long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("Kafka offsets cannot be negative");
        }
        long token = REGISTRATIONS.incrementAndGet();
        pending.computeIfAbsent(Objects.requireNonNull(partition, "partition"), _ -> new ArrayDeque<>())
                .addLast(new Entry(offset, token));
        return token;
    }

    /** Marks a record successful after its durable outcome has completed. */
    public void complete(TopicPartition partition, long offset, long token) {
        if (!completeIfTracked(partition, offset, token)) {
            throw new IllegalStateException("Completed record was not registered");
        }
    }

    /** Completes a record unless its partition was revoked before the worker finished. */
    public boolean completeIfTracked(TopicPartition partition, long offset, long token) {
        ArrayDeque<Entry> entries = pending.get(Objects.requireNonNull(partition, "partition"));
        if (entries == null) {
            return false;
        }
        for (Entry entry : entries) {
            if (entry.offset == offset && entry.token == token) {
                if (entry.complete) {
                    throw new IllegalStateException("Record completed more than once");
                }
                entry.complete = true;
                return true;
            }
        }
        return false;
    }

    /** Drops tracking after a rebalance revokes these partitions; they will replay from the committed offset. */
    public void discard(Set<TopicPartition> partitions) {
        partitions.forEach(pending::remove);
    }

    /** Drains contiguous completed prefixes, returning offsets Kafka can safely commit. */
    public Map<TopicPartition, OffsetAndMetadata> readyToCommit() {
        return readyToCommit(Set.copyOf(pending.keySet()));
    }

    /** Drains completed prefixes for only the supplied partitions. */
    public Map<TopicPartition, OffsetAndMetadata> readyToCommit(Set<TopicPartition> partitions) {
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        partitions.forEach(partition -> {
            ArrayDeque<Entry> entries = pending.get(partition);
            if (entries == null) {
                return;
            }
            long nextOffset = -1;
            while (!entries.isEmpty() && entries.peekFirst().complete) {
                nextOffset = entries.removeFirst().offset + 1;
            }
            if (nextOffset >= 0) {
                offsets.put(partition, new OffsetAndMetadata(nextOffset));
            }
        });
        partitions.forEach(partition -> {
            ArrayDeque<Entry> entries = pending.get(partition);
            if (entries != null && entries.isEmpty()) {
                pending.remove(partition);
            }
        });
        return Map.copyOf(offsets);
    }

    private static final class Entry {
        private final long offset;
        private final long token;
        private boolean complete;

        private Entry(long offset, long token) {
            this.offset = offset;
            this.token = token;
        }
    }
}
