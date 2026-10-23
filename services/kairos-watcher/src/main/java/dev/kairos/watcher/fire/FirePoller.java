package dev.kairos.watcher.fire;

import dev.kairos.domain.schedule.FirePlan;
import dev.kairos.domain.schedule.MisfireHandler;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fires schedules only while a generation-fenced shard lease is held. */
public final class FirePoller implements AutoCloseable {
    private final ShardedPoller.Registration registration;
    private final FireScheduleStore store;
    private final FireMaterializer materializer;
    private final MisfireHandler misfires;
    private final Clock clock;
    private final Duration horizon;
    private final Duration backPressureAge;
    private final MeterRegistry meters;

    /** Registers the one-second fire cycle with the shared, lease-guarded poller. */
    public FirePoller(
            ShardedPoller scheduler,
            FireScheduleStore store,
            FireMaterializer materializer,
            Clock clock,
            Duration horizon,
            Duration backPressureAge,
            MeterRegistry meters) {
        this.store = Objects.requireNonNull(store, "store");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.horizon = Objects.requireNonNull(horizon, "horizon");
        this.backPressureAge = Objects.requireNonNull(backPressureAge, "backPressureAge");
        this.meters = Objects.requireNonNull(meters, "meters");
        if (horizon.isNegative() || backPressureAge.isNegative() || backPressureAge.isZero()) {
            throw new IllegalArgumentException("Invalid fire timing");
        }
        this.misfires = MisfireHandler.standard();
        this.registration =
                Objects.requireNonNull(scheduler, "scheduler").register("fire", Duration.ofSeconds(1), 8, this::poll);
    }

    /** Processes one shard in the surrounding cell transaction. */
    public void poll(Cell cell, int shard) {
        Instant now = clock.instant();
        if (store.oldestDue(cell, now)
                .filter(oldest -> oldest.isBefore(now.minus(backPressureAge)))
                .isPresent()) {
            Counter.builder("kairos_fire_backpressure_total")
                    .tag("cell", Integer.toString(cell.id()))
                    .register(meters)
                    .increment();
            return;
        }
        Timer.Sample timer = Timer.start(meters);
        AtomicInteger batch = new AtomicInteger();
        AtomicInteger fires = new AtomicInteger();
        try {
            for (FireSchedule schedule : store.claim(cell, shard, now.plus(horizon))) {
                FirePlan plan = schedule.plan(misfires, now, horizon);
                if (!plan.fires().isEmpty()) fires.addAndGet(materializer.create(cell, schedule, plan.fires()));
                store.advance(cell, schedule, plan);
                batch.incrementAndGet();
                if (plan.misfired()) {
                    onCommit(() -> Counter.builder("kairos_misfires_total")
                            .tag("cell", Integer.toString(cell.id()))
                            .tag("policy", schedule.misfirePolicy().name())
                            .register(meters)
                            .increment());
                }
            }
            onCommit(() -> {
                Counter.builder("kairos_fires_total")
                        .tag("cell", Integer.toString(cell.id()))
                        .register(meters)
                        .increment(fires.get());
                meters.summary("kairos_poll_batch_size", "poller", "fire", "cell", Integer.toString(cell.id()))
                        .record(batch.get());
            });
        } finally {
            timer.stop(Timer.builder("kairos_poll_duration_seconds")
                    .tag("poller", "fire")
                    .tag("cell", Integer.toString(cell.id()))
                    .register(meters));
        }
    }

    private static void onCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Fire poller requires a cell transaction");
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
