package dev.kairos.executor.consume.infrastructure;

import dev.kairos.executor.consume.application.DispatchAdmission;
import dev.kairos.executor.consume.application.DispatchProcessor;
import dev.kairos.messaging.client.KafkaClientFactory;
import dev.kairos.messaging.client.PoisonMessages;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.SmartLifecycle;

/** Starts and stops the three independent priority poll loops. */
public final class DispatchConsumerManager implements SmartLifecycle {

    private final Map<DispatchTier, DispatchConsumerRunner> runners = new EnumMap<>(DispatchTier.class);
    private volatile boolean running;

    /** Creates pollers with fixed per-tier batches and a shared pod capacity. */
    public DispatchConsumerManager(
            KafkaClientFactory factory,
            DispatchProcessor processor,
            DispatchAdmission admission,
            PoisonMessages poisonMessages,
            DispatchCapacity capacity,
            ExecutorConsumerProperties properties,
            ExecutorConsumerMetrics metrics) {
        Objects.requireNonNull(properties, "properties");
        for (DispatchTier tier : DispatchTier.values()) {
            runners.put(
                    tier,
                    new DispatchConsumerRunner(
                            tier,
                            "kairos-executor",
                            factory,
                            processor,
                            admission,
                            poisonMessages,
                            capacity,
                            new dev.kairos.messaging.client.PartitionFlowControl(
                                    properties.highWater(), properties.lowWater()),
                            properties,
                            metrics));
        }
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        runners.values().forEach(DispatchConsumerRunner::start);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        runners.values().forEach(DispatchConsumerRunner::stop);
        runners.values().forEach(runner -> {
            try {
                runner.join(Duration.ofSeconds(30));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return 0;
    }
}
