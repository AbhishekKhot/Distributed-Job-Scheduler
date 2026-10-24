package dev.kairos.watcher.outbox;

import dev.kairos.domain.model.OutboxTopic;
import dev.kairos.messaging.client.KafkaPublisher;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publishes committed outbox rows and deletes only Kafka-acknowledged records. */
public final class OutboxRelay implements AutoCloseable {
    static final int BATCH_SIZE = 2_000;

    private final ShardedPoller.Registration registration;
    private final OutboxStore store;
    private final KafkaPublisher publisher;
    private final RelayRateLimiter limiter;
    private final Clock clock;
    private final OutboxMetrics metrics;
    private final ConcurrentHashMap<Integer, AtomicLong> measuredSeconds = new ConcurrentHashMap<>();

    /** Registers the 100 ms relay with the generation-fenced shard poller. */
    public OutboxRelay(
            ShardedPoller scheduler,
            OutboxStore store,
            KafkaPublisher publisher,
            RelayRateLimiter limiter,
            Clock clock,
            OutboxMetrics metrics) {
        this.store = Objects.requireNonNull(store, "store");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        registration = Objects.requireNonNull(scheduler, "scheduler")
                .register("outbox", Duration.ofMillis(100), 8, this::poll);
    }

    /** Relays one owned shard within the caller's lease-guarded cell transaction. */
    public void poll(Cell cell, int shard) {
        Instant now = clock.instant();
        Timer.Sample timer = metrics.start();
        try {
            int permits = limiter.reserve(BATCH_SIZE, now);
            List<OutboxRow> rows = permits == 0 ? List.of() : store.claim(cell, shard, now, permits);
            limiter.refund(permits - rows.size());
            if (!rows.isEmpty()) publish(cell, shard, rows);
            updateBacklog(cell, clock.instant());
        } finally {
            timer.stop(metrics.timer(cell));
        }
    }

    private void publish(Cell cell, int shard, List<OutboxRow> rows) {
        List<CompletableFuture<RecordMetadata>> futures =
                publisher.publish(rows.stream().map(OutboxRow::record).toList());
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .handle((ignored, failure) -> null)
                .join();

        List<UUID> acknowledged = new ArrayList<>();
        List<OutboxTopic> publishedTopics = new ArrayList<>();
        Map<Instant, List<UUID>> retries = new HashMap<>();
        Instant retryNow = clock.instant();
        for (int index = 0; index < rows.size(); index++) {
            OutboxRow row = rows.get(index);
            if (futures.get(index).isCompletedExceptionally()
                    || futures.get(index).isCancelled()) {
                Instant retryAt = retryNow.plus(retryDelay(row.createdAt(), retryNow));
                retries.computeIfAbsent(retryAt, ignored -> new ArrayList<>()).add(row.id());
            } else {
                acknowledged.add(row.id());
                publishedTopics.add(row.topic());
            }
        }
        store.deleteAcknowledged(cell, shard, acknowledged);
        retries.forEach((due, ids) -> store.deferFailed(cell, shard, ids, due));
        onCommit(() -> publishedTopics.forEach(topic -> metrics.published(cell, topic)));
    }

    private void updateBacklog(Cell cell, Instant now) {
        AtomicLong measured = measuredSeconds.computeIfAbsent(cell.id(), ignored -> new AtomicLong(Long.MIN_VALUE));
        long second = now.getEpochSecond();
        if (measured.get() == second) return;
        OutboxStore.Backlog backlog = store.backlog(cell, now);
        onCommit(() -> {
            metrics.backlog(cell, backlog, now);
            measured.set(second);
        });
    }

    private static Duration retryDelay(Instant createdAt, Instant now) {
        long ageSeconds = Math.max(0, Duration.between(createdAt, now).getSeconds());
        int exponent = (int) Math.min(5, ageSeconds / 10);
        return Duration.ofSeconds(Math.min(30, 1L << exponent));
    }

    private static void onCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Outbox relay requires a cell transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    @Override
    public void close() {
        registration.close();
    }
}
