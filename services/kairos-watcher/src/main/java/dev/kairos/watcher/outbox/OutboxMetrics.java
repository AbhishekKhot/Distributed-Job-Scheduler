package dev.kairos.watcher.outbox;

import dev.kairos.domain.model.OutboxTopic;
import dev.kairos.persistence.cell.Cell;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Cell backlog gauges and acknowledged publish counters. */
public final class OutboxMetrics {
    private final MeterRegistry registry;
    private final ConcurrentHashMap<Integer, CellMeters> cells = new ConcurrentHashMap<>();

    /** Registers metrics in the supplied service registry. */
    public OutboxMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Updates cell-wide backlog gauges from a committed poll. */
    public void backlog(Cell cell, OutboxStore.Backlog backlog, Instant now) {
        CellMeters meters = cells.computeIfAbsent(cell.id(), id -> register(cell));
        meters.rows.set(backlog.rows());
        Instant oldest = backlog.oldestDue();
        meters.oldestMillis.set(
                oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toMillis()));
    }

    /** Counts an acknowledged record after its outbox deletion commits. */
    public void published(Cell cell, OutboxTopic topic) {
        registry.counter(
                        "kairos_outbox_published_total",
                        "cell",
                        cell.range().datasourceRef(),
                        "topic",
                        topic.topicName())
                .increment();
    }

    /** Records one relay batch's wall-clock processing duration. */
    public Timer.Sample start() {
        return Timer.start(registry);
    }

    /** Returns the timer for a cell's relay batches. */
    public Timer timer(Cell cell) {
        return Timer.builder("kairos_outbox_batch_duration_seconds")
                .tag("cell", cell.range().datasourceRef())
                .register(registry);
    }

    private CellMeters register(Cell cell) {
        String label = cell.range().datasourceRef();
        AtomicLong rows = new AtomicLong();
        AtomicLong oldestMillis = new AtomicLong();
        Gauge.builder("kairos_outbox_backlog_rows", rows, AtomicLong::get)
                .tag("cell", label)
                .register(registry);
        Gauge.builder("kairos_outbox_oldest_due_seconds", oldestMillis, value -> value.get() / 1_000d)
                .tag("cell", label)
                .register(registry);
        return new CellMeters(rows, oldestMillis);
    }

    private record CellMeters(AtomicLong rows, AtomicLong oldestMillis) {}
}
