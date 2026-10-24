package dev.kairos.watcher.recovery;

import dev.kairos.engine.outbox.OutboxMessage;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.engine.run.DispatchMessageBuilder;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.watcher.delayed.DelayedDispatchLoader;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Redispatches queued runs whose dispatch has not been claimed for ten minutes. */
public final class StuckQueuedSweeper implements AutoCloseable {
    private final ShardedPoller.Registration registration;
    private final RecoveryRunStore store;
    private final DelayedDispatchLoader loader;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final MeterRegistry meters;

    /** Registers a one-minute sweep under the shard lease guard. */
    public StuckQueuedSweeper(
            ShardedPoller scheduler,
            RecoveryRunStore store,
            DelayedDispatchLoader loader,
            OutboxWriter outbox,
            Clock clock,
            MeterRegistry meters) {
        this.store = Objects.requireNonNull(store, "store");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
        registration = scheduler.register("stuck-queued", Duration.ofMinutes(1), 8, this::poll);
    }

    /** Redispatches one shard in its fenced cell transaction. */
    public void poll(Cell cell, int shard) {
        Instant now = clock.instant();
        for (RecoveryRun claimed : store.stuck(cell, shard, now)) {
            var loaded = RecoveryDispatch.load(loader, cell, claimed.run());
            var dispatch = DispatchMessageBuilder.redispatch(claimed.run(), loaded.call(), now, null);
            store.queued(cell, claimed, dispatch);
            outbox.write(OutboxMessage.dispatch(dispatch.message(), now));
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException("Recovery requires a cell transaction");
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    meters.counter("kairos_stuck_queued_redispatch_total", "cell", Integer.toString(cell.id()))
                            .increment();
                }
            });
        }
    }

    @Override
    public void close() {
        registration.close();
    }
}
