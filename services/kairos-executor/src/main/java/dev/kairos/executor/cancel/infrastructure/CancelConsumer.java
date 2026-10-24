package dev.kairos.executor.cancel.infrastructure;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.run.Fence;
import dev.kairos.messaging.client.KafkaClientFactory;
import dev.kairos.messaging.client.KairosConsumer;
import dev.kairos.messaging.serde.Decoded;
import dev.kairos.messaging.serde.MessageType;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Cancel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.springframework.context.SmartLifecycle;

/** Broadcast cancel consumer; its group id is unique to this executor pod. */
public final class CancelConsumer implements SmartLifecycle {
    private static final System.Logger LOG = System.getLogger(CancelConsumer.class.getName());
    private final KafkaClientFactory factory;
    private final InFlightCalls calls;
    private final String groupId;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private volatile Consumer<byte[], Decoded<Cancel>> consumer;
    private volatile Thread thread;

    /** Creates the per-pod cancel subscriber. */
    public CancelConsumer(KafkaClientFactory factory, InFlightCalls calls, String podId) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.calls = Objects.requireNonNull(calls, "calls");
        String safePodId = Objects.requireNonNull(podId, "podId").replaceAll("[^A-Za-z0-9.-]", "-");
        this.groupId = "kairos-executor-cancel-" + safePodId;
        if (groupId.length() > 128) throw new IllegalArgumentException("Executor pod id is too long");
    }

    @Override
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofPlatform().name("kairos-cancel-listener").start(this::consume);
        }
    }

    private void consume() {
        while (running.get()) {
            try (KairosConsumer<Cancel> wrapped = factory.consumer(groupId, MessageType.CANCEL, 100)) {
                Consumer<byte[], Decoded<Cancel>> active = wrapped.consumer();
                consumer = active;
                active.subscribe(List.of("kairos.run.cancel"));
                while (running.get()) {
                    var records = active.poll(Duration.ofMillis(250));
                    if (!active.assignment().isEmpty()) ready.set(true);
                    for (ConsumerRecord<byte[], Decoded<Cancel>> record : records) {
                        if (record.value() instanceof Decoded.Valid<Cancel> valid) {
                            cancel(valid.message());
                        }
                        active.commitSync(Map.of(
                                new TopicPartition(record.topic(), record.partition()),
                                new org.apache.kafka.clients.consumer.OffsetAndMetadata(record.offset() + 1)));
                    }
                }
            } catch (WakeupException wakeup) {
                if (running.get()) {
                    LOG.log(System.Logger.Level.WARNING, "Cancel consumer woke unexpectedly; reconnecting");
                }
            } catch (RuntimeException failure) {
                if (running.get()) {
                    LOG.log(
                            System.Logger.Level.ERROR,
                            "Cancel listener reconnecting after "
                                    + failure.getClass().getSimpleName());
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        running.set(false);
                    }
                }
            } finally {
                consumer = null;
                ready.set(false);
            }
        }
    }

    private void cancel(Cancel cancel) {
        if (cancel.getRunId().size() != 16
                || cancel.getShard() < 0
                || cancel.getShard() > 255
                || cancel.getFence() < 0) {
            return;
        }
        calls.cancel(new RunId(ProtoIds.toUuid(cancel.getRunId())), new Fence(cancel.getFence()));
    }

    @Override
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            Consumer<byte[], Decoded<Cancel>> active = consumer;
            if (active != null) active.wakeup();
            Thread activeThread = thread;
            if (activeThread != null) {
                try {
                    activeThread.join(Duration.ofSeconds(5).toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** Tells whether Kafka assigned this pod its cancel partitions. */
    public boolean isReady() {
        return ready.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return 10;
    }
}
