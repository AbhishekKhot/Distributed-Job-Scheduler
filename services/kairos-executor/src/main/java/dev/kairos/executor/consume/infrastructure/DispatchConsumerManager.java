package dev.kairos.executor.consume.infrastructure;

import dev.kairos.executor.cancel.infrastructure.InFlightCalls;
import dev.kairos.executor.consume.application.DispatchAdmission;
import dev.kairos.executor.consume.application.DispatchProcessor;
import dev.kairos.executor.lifecycle.infrastructure.JdbcDrainRequeuer;
import dev.kairos.messaging.client.KafkaClientFactory;
import dev.kairos.messaging.client.PoisonMessages;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;

/** Starts and stops the three independent priority poll loops. */
public final class DispatchConsumerManager implements SmartLifecycle {

    private final Map<DispatchTier, DispatchConsumerRunner> runners = new EnumMap<>(DispatchTier.class);
    private final ApplicationEventPublisher events;
    private final InFlightCalls inFlight;
    private final JdbcDrainRequeuer requeuer;
    private volatile boolean running;

    /** Creates pollers with fixed per-tier batches and a shared pod capacity. */
    public DispatchConsumerManager(
            KafkaClientFactory factory,
            DispatchProcessor processor,
            DispatchAdmission admission,
            PoisonMessages poisonMessages,
            DispatchCapacity capacity,
            ExecutorConsumerProperties properties,
            ExecutorConsumerMetrics metrics,
            ApplicationEventPublisher events,
            InFlightCalls inFlight,
            JdbcDrainRequeuer requeuer) {
        Objects.requireNonNull(properties, "properties");
        this.events = Objects.requireNonNull(events, "events");
        this.inFlight = Objects.requireNonNull(inFlight, "inFlight");
        this.requeuer = Objects.requireNonNull(requeuer, "requeuer");
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
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        running = false;
        runners.values().forEach(DispatchConsumerRunner::stopGracefully);
        runners.values().forEach(runner -> {
            try {
                runner.join(Duration.ofSeconds(65));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            int requeued = inFlight.requeueUnsent(requeuer);
            if (requeued > 0) {
                System.getLogger(DispatchConsumerManager.class.getName())
                        .log(System.Logger.Level.INFO, "Requeued " + requeued + " unsent runs during executor drain");
            }
        } catch (RuntimeException failure) {
            System.getLogger(DispatchConsumerManager.class.getName())
                    .log(
                            System.Logger.Level.ERROR,
                            "Could not requeue every unsent run during drain; lease recovery will retry them");
        }
        inFlight.abortSentAfterDrain();
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
        return 20;
    }
}
