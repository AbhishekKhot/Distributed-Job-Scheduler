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

    /** Drains contiguous completed prefixes, returning offsets and slots that can be released. */
    public CommitBatch drainReady() {
        return drainReady(Set.copyOf(pending.keySet()));
    }

    /** Drains completed prefixes for only the supplied partitions. */
    public CommitBatch drainReady(Set<TopicPartition> partitions) {
        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        java.util.List<RecordRef> finished = new java.util.ArrayList<>();
        partitions.forEach(partition -> {
            ArrayDeque<Entry> entries = pending.get(partition);
            if (entries == null) {
                return;
            }
            long nextOffset = -1;
            while (!entries.isEmpty() && entries.peekFirst().complete) {
                Entry entry = entries.removeFirst();
                nextOffset = entry.offset + 1;
                finished.add(new RecordRef(partition, entry.offset, entry.token));
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
        return new CommitBatch(Map.copyOf(offsets), java.util.List.copyOf(finished));
    }

    /** Drops tracking after partitions are revoked; completed records behind a gap must release their slots and replay. */
    public java.util.List<RecordRef> discard(Set<TopicPartition> partitions) {
        java.util.List<RecordRef> finished = new java.util.ArrayList<>();
        partitions.forEach(partition -> {
            ArrayDeque<Entry> entries = pending.remove(partition);
            if (entries != null) {
                entries.stream()
                        .filter(entry -> entry.complete)
                        .map(entry -> new RecordRef(partition, entry.offset, entry.token))
                        .forEach(finished::add);
            }
        });
        return java.util.List.copyOf(finished);
    }

    /** Drops all partitions after a consumer stops; incomplete records replay after the consumer rejoins. */
    public java.util.List<RecordRef> discardAll() {
        return discard(Set.copyOf(pending.keySet()));
    }

    /** Safe Kafka offsets and completed records whose capacity slots can now be released. */
    public record CommitBatch(
            Map<TopicPartition, OffsetAndMetadata> offsets, java.util.List<RecordRef> finishedRecords) {}

    /** Identity of a consumed record, stable across rebalance and consumer restart. */
    public record RecordRef(TopicPartition partition, long offset, long token) {}

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
