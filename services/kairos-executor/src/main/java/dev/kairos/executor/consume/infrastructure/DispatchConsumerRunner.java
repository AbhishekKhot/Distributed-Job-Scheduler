package dev.kairos.executor.consume.infrastructure;

import dev.kairos.executor.consume.application.DispatchAdmission;
import dev.kairos.executor.consume.application.DispatchIds;
import dev.kairos.executor.consume.application.DispatchProcessor;
import dev.kairos.messaging.client.KafkaClientFactory;
import dev.kairos.messaging.client.KairosConsumer;
import dev.kairos.messaging.client.PartitionFlowControl;
import dev.kairos.messaging.client.PoisonMessages;
import dev.kairos.messaging.serde.Decoded;
import dev.kairos.messaging.serde.MessageType;
import dev.kairos.proto.v1.Dispatch;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

/** One Kafka poll thread and virtual-thread worker pool for a single priority tier. */
public final class DispatchConsumerRunner implements Runnable {

    private static final System.Logger LOG = System.getLogger(DispatchConsumerRunner.class.getName());
    private static final long MAX_RETRY_DELAY_MILLIS = Duration.ofSeconds(30).toMillis();

    private final DispatchTier tier;
    private final KafkaClientFactory factory;
    private final DispatchProcessor processor;
    private final DispatchAdmission admission;
    private final PoisonMessages poisonMessages;
    private final DispatchCapacity capacity;
    private final PartitionFlowControl flow;
    private final ExecutorConsumerProperties properties;
    private final ExecutorConsumerMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Consumer<byte[], Decoded<Dispatch>>> current = new AtomicReference<>();
    private final ConcurrentLinkedQueue<Completion> completed = new ConcurrentLinkedQueue<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Thread thread;

    /** Creates a tier runner. */
    public DispatchConsumerRunner(
            DispatchTier tier,
            KafkaClientFactory factory,
            DispatchProcessor processor,
            DispatchAdmission admission,
            PoisonMessages poisonMessages,
            DispatchCapacity capacity,
            PartitionFlowControl flow,
            ExecutorConsumerProperties properties,
            ExecutorConsumerMetrics metrics) {
        this.tier = Objects.requireNonNull(tier, "tier");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.poisonMessages = Objects.requireNonNull(poisonMessages, "poisonMessages");
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        metrics.bind(tier, flow);
    }

    /** Starts this tier's poll loop on a dedicated platform thread. */
    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofPlatform().name("kairos-dispatch-" + tier.label()).start(this);
        }
    }

    /** Stops polling and wakes Kafka's blocking poll. */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            Consumer<byte[], Decoded<Dispatch>> consumer = current.get();
            if (consumer != null) {
                consumer.wakeup();
            }
            Thread pollThread = thread;
            if (pollThread != null) {
                pollThread.interrupt();
            }
        }
    }

    /** Tells whether the poll loop is active. */
    public boolean isRunning() {
        return running.get();
    }

    /** Waits for the poll thread to leave its group and shut down its worker executor. */
    public void join(Duration timeout) throws InterruptedException {
        Thread pollThread = thread;
        if (pollThread != null) {
            pollThread.join(timeout.toMillis());
        }
    }

    @Override
    public void run() {
        try {
            while (running.get()) {
                try (KairosConsumer<Dispatch> wrapped =
                        factory.consumer("kairos-executor-" + tier.label(), MessageType.DISPATCH, tier.pollSize())) {
                    Consumer<byte[], Decoded<Dispatch>> consumer = wrapped.consumer();
                    current.set(consumer);
                    poll(consumer);
                } catch (WakeupException wakeup) {
                    if (running.get()) {
                        LOG.log(
                                System.Logger.Level.WARNING,
                                "Dispatch consumer woke unexpectedly: tier=" + tier.label());
                    }
                } catch (RuntimeException failure) {
                    if (running.get()) {
                        LOG.log(
                                System.Logger.Level.ERROR,
                                "Dispatch consumer restarting: tier=" + tier.label() + ", error="
                                        + failure.getClass().getSimpleName());
                    }
                } finally {
                    current.set(null);
                }
                if (running.get()) {
                    pauseBeforeRestart();
                }
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private void poll(Consumer<byte[], Decoded<Dispatch>> consumer) {
        DispatchOffsetTracker offsets = new DispatchOffsetTracker();
        AtomicBoolean globalPause = new AtomicBoolean();
        consumer.subscribe(
                List.of(tier.topic().topicName()), new org.apache.kafka.clients.consumer.ConsumerRebalanceListener() {
                    @Override
                    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                        // Flow control is applied on the next poll iteration.
                    }

                    @Override
                    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                        drainCompletions(offsets, consumer);
                        Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> safe =
                                offsets.readyToCommit(Set.copyOf(partitions));
                        if (!safe.isEmpty()) {
                            consumer.commitSync(safe);
                        }
                        offsets.discard(Set.copyOf(partitions));
                    }

                    @Override
                    public void onPartitionsLost(Collection<TopicPartition> partitions) {
                        offsets.discard(Set.copyOf(partitions));
                    }
                });

        while (running.get()) {
            drainCompletions(offsets, consumer);
            boolean flowPaused = flow.apply(consumer);
            if (!capacity.reserve(tier.pollSize())) {
                pauseAssigned(consumer);
                globalPause.set(true);
                consumer.poll(properties.pollTimeout());
                continue;
            }
            if (globalPause.getAndSet(false) && !flowPaused) {
                resumeAssigned(consumer);
            }
            ConsumerRecords<byte[], Decoded<Dispatch>> records;
            int count = 0;
            try {
                records = consumer.poll(properties.pollTimeout());
                count = records.count();
            } finally {
                capacity.release(tier.pollSize() - count);
            }
            try {
                metrics.poll(tier, count);
                metrics.lag(tier, consumer.metrics());
            } catch (RuntimeException metricFailure) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "Could not update dispatch consumer metrics: tier=" + tier.label() + ", error="
                                + metricFailure.getClass().getSimpleName());
            }
            for (ConsumerRecord<byte[], Decoded<Dispatch>> record : records) {
                TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                long token = offsets.register(partition, record.offset());
                var message = poisonMessages.message(record);
                if (message.isEmpty()) {
                    offsets.complete(partition, record.offset(), token);
                    capacity.release(1);
                    continue;
                }
                try {
                    DispatchIds.validateForAdmission(message.orElseThrow());
                } catch (IllegalArgumentException invalid) {
                    metrics.invalidDispatch();
                    offsets.complete(partition, record.offset(), token);
                    capacity.release(1);
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "Skipped a dispatch with invalid routing identifiers: tier=" + tier.label() + ", partition="
                                    + record.partition() + ", offset=" + record.offset());
                    continue;
                }
                flow.started(1);
                submit(record, message.orElseThrow(), partition, token);
            }
            drainCompletions(offsets, consumer);
        }
        drainCompletions(offsets, consumer);
    }

    private void submit(
            ConsumerRecord<byte[], Decoded<Dispatch>> record, Dispatch dispatch, TopicPartition partition, long token) {
        try {
            workers.execute(() -> process(dispatch, record, partition, token));
        } catch (RuntimeException rejected) {
            flow.finished(1);
            capacity.release(1);
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Dispatch worker rejected a record: tier=" + tier.label() + ", error="
                            + rejected.getClass().getSimpleName());
        }
    }

    private void process(
            Dispatch dispatch, ConsumerRecord<byte[], Decoded<Dispatch>> record, TopicPartition partition, long token) {
        boolean acquired = false;
        boolean finished = false;
        long retryDelayMillis = Duration.ofSeconds(1).toMillis();
        try {
            while (running.get()) {
                try {
                    if (!acquired) {
                        acquired = admission.acquireOrDefer(dispatch);
                        if (!acquired) {
                            finished = true;
                            completed.add(new Completion(partition, record.offset(), token));
                            return;
                        }
                    }
                    processor.process(dispatch);
                    finished = true;
                    completed.add(new Completion(partition, record.offset(), token));
                    return;
                } catch (Exception failure) {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "Dispatch processing did not reach a durable outcome; retrying: tier=" + tier.label()
                                    + ", partition=" + record.partition() + ", offset=" + record.offset()
                                    + ", error=" + failure.getClass().getSimpleName());
                    Thread.sleep(retryDelayMillis);
                    retryDelayMillis = Math.min(retryDelayMillis * 2, MAX_RETRY_DELAY_MILLIS);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired) {
                admission.release(dispatch);
            }
            if (finished || !running.get()) {
                flow.finished(1);
                capacity.release(1);
            }
        }
    }

    private void drainCompletions(DispatchOffsetTracker offsets, Consumer<byte[], Decoded<Dispatch>> consumer) {
        Completion completion;
        while ((completion = completed.poll()) != null) {
            offsets.completeIfTracked(completion.partition(), completion.offset(), completion.token());
        }
        Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> safe = offsets.readyToCommit();
        if (!safe.isEmpty()) {
            consumer.commitSync(safe);
        }
    }

    private static void pauseAssigned(Consumer<?, ?> consumer) {
        Set<TopicPartition> assignment = consumer.assignment();
        if (!assignment.isEmpty()) {
            consumer.pause(assignment);
        }
    }

    private static void resumeAssigned(Consumer<?, ?> consumer) {
        Set<TopicPartition> paused = consumer.paused();
        if (!paused.isEmpty()) {
            consumer.resume(paused);
        }
    }

    private void pauseBeforeRestart() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    private record Completion(TopicPartition partition, long offset, long token) {}
}
