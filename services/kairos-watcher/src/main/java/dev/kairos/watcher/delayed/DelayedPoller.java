package dev.kairos.watcher.delayed;

import dev.kairos.engine.outbox.OutboxMessage;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.engine.run.DispatchMessageBuilder;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Moves due delayed runs to queued and writes their fenced dispatches atomically. */
public final class DelayedPoller implements AutoCloseable {
    private final ShardedPoller.Registration registration;
    private final DelayedRunStore store;
    private final DelayedDispatchSource loader;
    private final DelayedPacer pacer;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final Duration suspensionDelay;
    private final MeterRegistry meters;

    /** Registers the one-second delayed cycle with the shared lease-guarded poller. */
    public DelayedPoller(
            ShardedPoller scheduler,
            DelayedRunStore store,
            DelayedDispatchSource loader,
            DelayedPacer pacer,
            OutboxWriter outbox,
            Clock clock,
            Duration suspensionDelay,
            MeterRegistry meters) {
        this.store = Objects.requireNonNull(store, "store");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.pacer = Objects.requireNonNull(pacer, "pacer");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.suspensionDelay = Objects.requireNonNull(suspensionDelay, "suspensionDelay");
        this.meters = Objects.requireNonNull(meters, "meters");
        if (suspensionDelay.isNegative() || suspensionDelay.isZero()) {
            throw new IllegalArgumentException("suspensionDelay must be positive");
        }
        registration = Objects.requireNonNull(scheduler, "scheduler")
                .register("delayed", Duration.ofSeconds(1), 8, this::poll);
    }

    /** Processes one shard inside the surrounding generation-fenced cell transaction. */
    public void poll(Cell cell, int shard) {
        Instant now = clock.instant();
        Timer.Sample timer = Timer.start(meters);
        AtomicInteger queued = new AtomicInteger();
        AtomicInteger deferred = new AtomicInteger();
        List<OutboxMessage> dispatches = new ArrayList<>();
        try {
            for (DelayedRun delayed : store.claim(cell, shard, now)) {
                DelayedDispatchLoader.LoadedDispatch loaded = loader.load(cell, delayed);
                if (loaded.suspended()) {
                    store.defer(cell, delayed, now, suspensionDelay);
                    deferred.incrementAndGet();
                    continue;
                }
                Instant retryAt = pacer.acquire(delayed, loaded.maxExecPerSecond(), now);
                if (retryAt != null) {
                    store.deferUntil(cell, delayed, retryAt);
                    deferred.incrementAndGet();
                    continue;
                }
                DispatchMessageBuilder.Redispatch dispatch =
                        DispatchMessageBuilder.redispatch(delayed.run(), loaded.call(), now, null);
                store.queued(cell, dispatch);
                dispatches.add(OutboxMessage.dispatch(dispatch.message(), now));
                queued.incrementAndGet();
            }
            outbox.write(dispatches);
            onCommit(() -> {
                meters.summary("kairos_poll_batch_size", "poller", "delayed", "cell", Integer.toString(cell.id()))
                        .record(queued.get() + deferred.get());
                meters.counter("kairos_delayed_dispatches_total", "cell", Integer.toString(cell.id()))
                        .increment(queued.get());
                meters.counter("kairos_delayed_deferrals_total", "cell", Integer.toString(cell.id()))
                        .increment(deferred.get());
            });
        } finally {
            timer.stop(Timer.builder("kairos_poll_duration_seconds")
                    .tag("poller", "delayed")
                    .tag("cell", Integer.toString(cell.id()))
                    .register(meters));
        }
    }

    private static void onCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Delayed poller requires a cell transaction");
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
