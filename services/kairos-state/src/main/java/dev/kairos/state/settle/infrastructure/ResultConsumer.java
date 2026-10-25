package dev.kairos.state.settle.infrastructure;

import dev.kairos.messaging.client.KairosConsumer;
import dev.kairos.messaging.client.PoisonMessages;
import dev.kairos.messaging.serde.Decoded;
import dev.kairos.messaging.serde.PoisonReason;
import dev.kairos.messaging.topic.KairosTopic;
import dev.kairos.proto.v1.Result;
import dev.kairos.state.settle.application.ResultMetadata;
import dev.kairos.state.settle.application.SettlementBatch;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.SmartLifecycle;

/** One Kafka poll thread commits a batch only after every owning cell committed. Failed polls rewind for replay. */
public final class ResultConsumer implements SmartLifecycle {
    private static final System.Logger LOG = System.getLogger(ResultConsumer.class.getName());
    private final Supplier<KairosConsumer<Result>> factory;
    private final SettlementBatch settlement;
    private final PoisonMessages poison;
    private final Duration pollTimeout;
    private final Duration retryDelay;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile @Nullable Consumer<byte[], Decoded<Result>> active;
    private @Nullable Thread thread;

    public ResultConsumer(
            Supplier<KairosConsumer<Result>> factory,
            SettlementBatch settlement,
            PoisonMessages poison,
            Duration pollTimeout,
            Duration retryDelay) {
        this.factory = factory;
        this.settlement = settlement;
        this.poison = poison;
        this.pollTimeout = pollTimeout;
        this.retryDelay = retryDelay;
    }

    @Override
    public synchronized void start() {
        if (running.compareAndSet(false, true))
            thread = Thread.ofPlatform().name("kairos-state-results").start(this::run);
    }

    private void run() {
        try (KairosConsumer<Result> client = factory.get()) {
            Consumer<byte[], Decoded<Result>> consumer = client.consumer();
            active = consumer;
            consumer.subscribe(List.of(KairosTopic.RUN_RESULT.topicName()));
            while (running.get()) {
                try {
                    pollOnce(consumer);
                } catch (WakeupException wakeup) {
                    if (running.get()) LOG.log(System.Logger.Level.WARNING, "Result consumer was woken while active");
                } catch (RuntimeException failure) {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "Result batch will replay: {0}",
                            failure.getClass().getSimpleName());
                    pause();
                }
            }
        } catch (RuntimeException failure) {
            LOG.log(
                    System.Logger.Level.ERROR,
                    "Result consumer stopped: {0}",
                    failure.getClass().getSimpleName());
        } finally {
            active = null;
            running.set(false);
        }
    }
    /** Processes one bounded poll; public to exercise real offset/replay behavior without lifecycle timing. */
    public boolean pollOnce(Consumer<byte[], Decoded<Result>> consumer) {
        var records = consumer.poll(pollTimeout);
        if (records.isEmpty()) return false;
        Map<TopicPartition, Long> first = new LinkedHashMap<>();
        Map<TopicPartition, OffsetAndMetadata> next = new LinkedHashMap<>();
        List<Result> valid = new ArrayList<>();
        for (var record : records) {
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            first.putIfAbsent(partition, record.offset());
            next.merge(
                    partition, new OffsetAndMetadata(record.offset() + 1), (a, b) -> a.offset() > b.offset() ? a : b);
            var decoded = poison.message(record);
            if (decoded.isEmpty()) continue;
            try {
                ResultMetadata.requireValid(decoded.get());
                valid.add(decoded.get());
            } catch (RuntimeException malformed) {
                poison.message(new ConsumerRecord<byte[], Decoded<Result>>(
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        record.key(),
                        new Decoded.Poison<>(PoisonReason.MALFORMED, Math.max(0, record.serializedValueSize()))));
            }
        }
        try {
            settlement.settle(valid);
            consumer.commitSync(next);
        } catch (RuntimeException failure) {
            for (var entry : first.entrySet())
                if (consumer.assignment().contains(entry.getKey())) consumer.seek(entry.getKey(), entry.getValue());
            throw failure;
        }
        return true;
    }

    private void pause() {
        try {
            Thread.sleep(retryDelay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    @Override
    public synchronized void stop() {
        running.set(false);
        var consumer = active;
        if (consumer != null) consumer.wakeup();
        Thread worker = thread;
        if (worker != null && worker != Thread.currentThread())
            try {
                worker.join(Duration.ofSeconds(30));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
