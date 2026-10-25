package dev.kairos.state.stats.application;

import dev.kairos.domain.model.Outcome;
import dev.kairos.state.settle.application.SettlementBatch.Settled;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import org.jspecify.annotations.Nullable;

/** Bounded cumulative counters for the current and previous settlement minute; never retains result payloads. */
public final class StatsBuffer {
    private final Clock clock;
    private final IntUnaryOperator cellForShard;
    private final int maximumBuckets;
    private final MeterRegistry meters;
    private final Map<Key, Bucket> buckets = new HashMap<>();
    private Instant latestMinute = Instant.MIN;
    private Instant expiredThrough = Instant.MIN;

    public StatsBuffer(Clock clock, IntUnaryOperator cellForShard, int maximumBuckets, MeterRegistry meters) {
        if (maximumBuckets < 2) throw new IllegalArgumentException("Statistics needs at least two buckets");
        this.clock = clock;
        this.cellForShard = cellForShard;
        this.maximumBuckets = maximumBuckets;
        this.meters = meters;
        meters.gauge("kairos.state.stats.buckets", this, StatsBuffer::size);
    }

    public synchronized void record(List<Settled> results) {
        Instant minute = minute();
        expire(minute);
        for (Settled settled : results) {
            if (settled.deferred()) continue;
            var run = settled.run();
            int cell = cellForShard.applyAsInt(run.shard().value());
            Key endpoint = new Key(cell, run.teamId().value(), run.endpointId().value(), minute);
            Key team = new Key(cell, run.teamId().value(), null, minute);
            int additional = (buckets.containsKey(endpoint) ? 0 : 1) + (buckets.containsKey(team) ? 0 : 1);
            if (buckets.size() + additional > maximumBuckets) {
                meters.counter("kairos.state.stats.dropped", "reason", "capacity")
                        .increment();
                continue;
            }
            Counts delta = new Counts(
                    1,
                    settled.outcome() == Outcome.SUCCEEDED ? 1 : 0,
                    settled.retryableFailure() ? 1 : 0,
                    settled.outcome() == Outcome.DEAD_LETTERED && !settled.retryableFailure() ? 1 : 0,
                    settled.outcome() == Outcome.DEAD_LETTERED ? 1 : 0,
                    settled.result().getLatencyMs(),
                    settled.result().getLatencyMs());
            buckets.computeIfAbsent(endpoint, ignored -> new Bucket()).add(delta);
            buckets.computeIfAbsent(team, ignored -> new Bucket()).add(delta);
        }
    }
    /** Returns immutable cumulative values for dirty buckets; I/O takes place without the buffer lock. */
    public synchronized List<Snapshot> snapshot() {
        return buckets.entrySet().stream()
                .filter(entry -> entry.getValue().dirty())
                .map(entry -> new Snapshot(entry.getKey(), entry.getValue().counts))
                .toList();
    }

    public synchronized void acknowledged(List<Snapshot> written) {
        for (var snapshot : written) {
            Bucket current = buckets.get(snapshot.key());
            if (current != null)
                current.persistedAttempts =
                        Math.max(current.persistedAttempts, snapshot.counts().attempts());
        }
    }

    public synchronized void evictExpired() {
        expire(minute());
    }

    public synchronized int size() {
        return buckets.size();
    }

    private Instant minute() {
        Instant current = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        // Re-entering an evicted minute after a clock rollback could overwrite its durable cumulative row.
        if (current.isAfter(latestMinute)) latestMinute = current;
        return latestMinute;
    }

    private void expire(Instant minute) {
        if (minute.equals(expiredThrough)) return;
        expiredThrough = minute;
        Instant cutoff = minute.minus(2, ChronoUnit.MINUTES);
        buckets.entrySet().removeIf(entry -> {
            if (entry.getKey().minute().isAfter(cutoff)) return false;
            if (entry.getValue().dirty())
                meters.counter("kairos.state.stats.evictions", "reason", "unflushed")
                        .increment();
            return true;
        });
    }
    /** A null endpoint identifies the team total; cell ownership is kept because definitions share control ids. */
    public record Key(int cellId, UUID teamId, @Nullable UUID endpointId, Instant minute) {}

    public record Snapshot(Key key, Counts counts) {}

    public record Counts(
            long attempts,
            long successes,
            long retryable,
            long nonRetryable,
            long deadLettered,
            long latencySumMs,
            int latencyMaxMs) {
        private Counts plus(Counts other) {
            return new Counts(
                    attempts + other.attempts,
                    successes + other.successes,
                    retryable + other.retryable,
                    nonRetryable + other.nonRetryable,
                    deadLettered + other.deadLettered,
                    latencySumMs + other.latencySumMs,
                    Math.max(latencyMaxMs, other.latencyMaxMs));
        }
    }

    private static final class Bucket {
        private Counts counts = new Counts(0, 0, 0, 0, 0, 0, 0);
        private long persistedAttempts;

        void add(Counts delta) {
            counts = counts.plus(delta);
        }

        boolean dirty() {
            return counts.attempts() > persistedAttempts;
        }
    }
}
