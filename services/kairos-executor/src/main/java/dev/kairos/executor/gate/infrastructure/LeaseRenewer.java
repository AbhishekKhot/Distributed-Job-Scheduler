package dev.kairos.executor.gate.infrastructure;

import dev.kairos.engine.lock.ExtendResult;
import dev.kairos.engine.lock.RunLock;
import dev.kairos.engine.lock.RunLockPort;
import dev.kairos.executor.gate.application.ExecutionContext;
import dev.kairos.executor.gate.application.RunClaimPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Renews the Redis lock and matching cell lease together at half the lock TTL. */
public final class LeaseRenewer implements AutoCloseable {

    private static final String METRIC = "kairos.execution.lease.renewal";

    private final ExecutionContext context;
    private final RunLockPort locks;
    private final RunClaimPort claims;
    private final Clock clock;
    private final MeterRegistry meters;
    private final ScheduledFuture<?> task;

    /** Starts renewal immediately after the fenced claim. */
    public LeaseRenewer(
            ExecutionContext context,
            RunLockPort locks,
            RunClaimPort claims,
            Clock clock,
            MeterRegistry meters,
            ScheduledExecutorService scheduler) {
        this.context = Objects.requireNonNull(context, "context");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
        long intervalMillis = Math.max(1, context.lockTtl().dividedBy(2).toMillis());
        this.task = Objects.requireNonNull(scheduler, "scheduler")
                .scheduleAtFixedRate(this::renew, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        task.cancel(false);
    }

    private void renew() {
        RunLock lock = context.lock().orElseThrow();
        ExtendResult extended = locks.extend(lock, context.lockTtl());
        if (extended != ExtendResult.EXTENDED) {
            failed();
            return;
        }
        Instant leaseUntil = clock.instant().plus(context.lockTtl());
        try {
            if (!claims.renew(context, lock.token().nodeId(), leaseUntil)) {
                failed();
                return;
            }
            context.renewed(leaseUntil);
            meters.counter(METRIC, "outcome", "renewed").increment();
        } catch (RuntimeException failure) {
            failed();
        }
    }

    private void failed() {
        context.loseLease();
        meters.counter(METRIC, "outcome", "lost").increment();
        task.cancel(false);
    }
}
