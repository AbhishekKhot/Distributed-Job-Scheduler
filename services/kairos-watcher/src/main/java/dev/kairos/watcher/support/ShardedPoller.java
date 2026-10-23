package dev.kairos.watcher.support;

import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.watcher.lease.OwnedShards;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Schedules bounded shard groups and runs each callback under a database lease guard. */
public final class ShardedPoller implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ShardedPoller.class);
    private static final String GUARD = """
            SELECT generation FROM scheduler.shard_leases
             WHERE shard_id = :shard AND owner_node = :node AND generation = :generation
               AND lease_until > clock_timestamp()
             FOR SHARE
            """;

    private final CellRouter cells;
    private final OwnedShards owned;
    private final String nodeId;
    private final Clock clock;
    private final Duration jitter;
    private final int defaultGroupSize;
    private final MeterRegistry meters;
    private final ScheduledExecutorService executor;
    private final List<Registration> registrations = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean closed;

    /**
     * Creates the poller scheduler.
     * @param cells cell lookup
     * @param owned current local lease view
     * @param nodeId this watcher identity
     * @param clock clock for local lease deadlines
     * @param jitter maximum delay added to or subtracted from a polling interval
     * @param meters metrics registry
     */
    public ShardedPoller(
            CellRouter cells, OwnedShards owned, String nodeId, Clock clock, Duration jitter, MeterRegistry meters) {
        this(cells, owned, nodeId, clock, jitter, 8, meters);
    }

    /** Creates the poller scheduler with a configured default shard group size. */
    public ShardedPoller(
            CellRouter cells,
            OwnedShards owned,
            String nodeId,
            Clock clock,
            Duration jitter,
            int defaultGroupSize,
            MeterRegistry meters) {
        this.cells = Objects.requireNonNull(cells, "cells");
        this.owned = Objects.requireNonNull(owned, "owned");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        if (defaultGroupSize < 1 || defaultGroupSize > 64) throw new IllegalArgumentException("Invalid group size");
        this.defaultGroupSize = defaultGroupSize;
        this.meters = Objects.requireNonNull(meters, "meters");
        if (jitter.isNegative()) throw new IllegalArgumentException("jitter must not be negative");
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(4, task -> {
            Thread thread = new Thread(task, "kairos-shard-poller");
            thread.setDaemon(true);
            return thread;
        });
        pool.setRemoveOnCancelPolicy(true);
        this.executor = pool;
    }

    /**
     * Registers a poll callback. A callback runs once per owned shard in a bounded group and transaction.
     * @param name low-cardinality poller name used in metrics
     * @param interval nominal time between cycles
     * @param groupSize maximum shards scheduled together
     * @param callback work to perform for one shard
     * @return a handle that stops this registration
     */
    public Registration register(String name, Duration interval, int groupSize, ShardWork callback) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(callback, "callback");
        if (!name.matches("[a-z][a-z0-9-]{0,31}")
                || interval.isZero()
                || interval.isNegative()
                || groupSize < 1
                || groupSize > 64
                || jitter.compareTo(interval) >= 0) {
            throw new IllegalArgumentException("Invalid sharded poller registration");
        }
        Registration registration = new Registration(name, interval, groupSize, callback);
        if (closed) throw new IllegalStateException("Sharded poller is closed");
        registrations.add(registration);
        scheduleCycle(registration, 0);
        return registration;
    }

    /** Registers a poll callback using the configured shard group size. */
    public Registration register(String name, Duration interval, ShardWork callback) {
        return register(name, interval, defaultGroupSize, callback);
    }

    private void scheduleCycle(Registration registration, long delayMillis) {
        if (!closed && !registration.stopped.get()) {
            registration.next = executor.schedule(() -> cycle(registration), delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void cycle(Registration registration) {
        if (closed || registration.stopped.get()) return;
        List<Cell> layout;
        try {
            layout = cells.allCells();
        } catch (RuntimeException failure) {
            LOG.warn(
                    "Poller {} could not load cell layout: {}",
                    registration.name,
                    failure.getClass().getSimpleName());
            scheduleCycle(registration, nextDelay(registration.interval));
            return;
        }
        Map<Integer, Cell> byId = layout.stream().collect(java.util.stream.Collectors.toMap(Cell::id, cell -> cell));
        List<Group> groups = new ArrayList<>();
        owned.snapshot().forEach((cellId, leases) -> {
            Cell cell = byId.get(cellId);
            if (cell != null) {
                for (int start = 0; start < leases.size(); start += registration.groupSize) {
                    groups.add(new Group(
                            cell, leases.subList(start, Math.min(start + registration.groupSize, leases.size()))));
                }
            }
        });
        if (groups.isEmpty()) {
            scheduleCycle(registration, nextDelay(registration.interval));
            return;
        }
        AtomicInteger completed = new AtomicInteger();
        for (Group group : groups) {
            long delay = randomJitterMillis();
            executor.schedule(
                    () -> {
                        try {
                            runGroup(registration, group);
                        } finally {
                            if (completed.incrementAndGet() == groups.size())
                                scheduleCycle(registration, nextDelay(registration.interval));
                        }
                    },
                    delay,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void runGroup(Registration registration, Group group) {
        for (OwnedShards.Lease lease : group.leases()) {
            if (closed || registration.stopped.get()) return;
            if (!owned.mayStart(group.cell().id(), lease.shard(), lease.generation(), clock.instant())) continue;
            Database database = group.cell().database();
            Timer.Sample timer = Timer.start(meters);
            try {
                database.transactions().execute(transactionJdbc -> {
                    boolean valid = transactionJdbc
                            .sql(GUARD)
                            .param("shard", lease.shard())
                            .param("node", nodeId)
                            .param("generation", lease.generation())
                            .query(Long.class)
                            .optional()
                            .isPresent();
                    if (valid
                            && owned.mayStart(group.cell().id(), lease.shard(), lease.generation(), clock.instant())) {
                        registration.callback.process(group.cell(), lease.shard());
                    }
                    return null;
                });
            } catch (RuntimeException failure) {
                LOG.warn(
                        "Poller {} failed on cell {} shard {}: {}",
                        registration.name,
                        group.cell().id(),
                        lease.shard(),
                        failure.getClass().getSimpleName());
            } finally {
                timer.stop(Timer.builder("kairos_shard_poller_duration")
                        .tag("poller", registration.name)
                        .tag("cell", Integer.toString(group.cell().id()))
                        .register(meters));
            }
        }
    }

    private long nextDelay(Duration interval) {
        long nominal = interval.toMillis();
        long spread = Math.min(jitter.toMillis(), Math.max(0, nominal - 1));
        return nominal - spread + randomInclusive(spread * 2);
    }

    private long randomJitterMillis() {
        return randomInclusive(jitter.toMillis());
    }

    private static long randomInclusive(long max) {
        return max == 0 ? 0 : ThreadLocalRandom.current().nextLong(max + 1);
    }

    /** Stops every registration and waits briefly for active shard transactions. */
    @Override
    public void close() {
        closed = true;
        registrations.forEach(Registration::close);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Work executed for one shard inside a transaction holding its lease row lock. */
    @FunctionalInterface
    public interface ShardWork {
        /**
         * Processes one shard.
         * @param cell the shard's cell
         * @param shard shard number
         */
        void process(Cell cell, int shard);
    }

    /** Handle for stopping a registered polling task. */
    public final class Registration implements AutoCloseable {
        private final String name;
        private final Duration interval;
        private final int groupSize;
        private final ShardWork callback;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile ScheduledFuture<?> next;

        private Registration(String name, Duration interval, int groupSize, ShardWork callback) {
            this.name = name;
            this.interval = interval;
            this.groupSize = groupSize;
            this.callback = callback;
        }

        /** Stops future cycles; an active transaction completes or is interrupted at service shutdown. */
        @Override
        public void close() {
            stopped.set(true);
            ScheduledFuture<?> scheduled = next;
            if (scheduled != null) scheduled.cancel(false);
            registrations.remove(this);
        }
    }

    private record Group(Cell cell, List<OwnedShards.Lease> leases) {}
}
