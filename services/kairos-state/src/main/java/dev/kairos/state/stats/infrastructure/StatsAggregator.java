package dev.kairos.state.stats.infrastructure;

import dev.kairos.state.settle.application.SettlementBatch;
import dev.kairos.state.stats.application.StatsBuffer;
import dev.kairos.state.stats.application.StatsWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;

/** Observes only committed results and drains after the result consumer stops during shutdown. */
public final class StatsAggregator implements SettlementBatch.Observer, SmartLifecycle {
    private static final System.Logger LOG = System.getLogger(StatsAggregator.class.getName());
    private final String nodeId;
    private final StatsBuffer buffer;
    private final StatsWriter writer;
    private final MeterRegistry meters;
    private final ReentrantLock flushing = new ReentrantLock();
    private volatile boolean running;

    public StatsAggregator(String nodeId, StatsBuffer buffer, StatsWriter writer, MeterRegistry meters) {
        if (nodeId.isBlank() || nodeId.length() > 128)
            throw new IllegalArgumentException("Invalid statistics node identity");
        this.nodeId = nodeId;
        this.buffer = buffer;
        this.writer = writer;
        this.meters = meters;
    }

    @Override
    public void committed(List<SettlementBatch.Settled> results) {
        buffer.record(results);
    }

    @Scheduled(fixedDelayString = "${kairos.state.stats.flush-interval:10s}")
    public void scheduled() {
        if (running) flushSafely();
    }

    public int flush() {
        flushing.lock();
        try {
            var snapshots = buffer.snapshot();
            if (snapshots.isEmpty()) return 0;
            writer.write(nodeId, snapshots);
            buffer.acknowledged(snapshots);
            meters.counter("kairos.state.stats.flushes").increment();
            return snapshots.size();
        } finally {
            try {
                buffer.evictExpired();
            } finally {
                flushing.unlock();
            }
        }
    }

    private void flushSafely() {
        try {
            flush();
        } catch (RuntimeException failure) {
            meters.counter("kairos.state.stats.flush.failures").increment();
            LOG.log(
                    System.Logger.Level.WARNING,
                    "Statistics flush will retry within the retention window: {0}",
                    failure.getClass().getSimpleName());
        }
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        flushSafely();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 200;
    }

    public String nodeId() {
        return nodeId;
    }
    /** A unique process incarnation prevents a restart from replacing an earlier process's rows. */
    public static String nodeId(String pod, Clock clock) {
        String safe = pod.replaceAll("[^A-Za-z0-9-]", "-");
        if (safe.isBlank()) safe = "local";
        if (safe.length() > 40) safe = safe.substring(0, 40);
        return "kairos-state-" + safe + "-" + clock.millis() + "-" + UUID.randomUUID();
    }
}
