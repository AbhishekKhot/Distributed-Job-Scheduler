package dev.kairos.executor.gate.application;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.run.Fence;
import dev.kairos.engine.lock.RunLock;
import dev.kairos.proto.v1.Dispatch;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/** Per-dispatch state shared by the execution gates and the execution action. */
public final class ExecutionContext {

    private final Dispatch dispatch;
    private final RunId runId;
    private final Fence dispatchFence;
    private final Duration timeout;
    private final Duration lockTtl;
    private final AtomicBoolean leaseLost = new AtomicBoolean();
    private volatile @Nullable RunLock lock;
    private volatile @Nullable Fence claimedFence;
    private volatile @Nullable Instant leaseUntil;

    /** Creates the immutable dispatch facts used by the gates. */
    public ExecutionContext(Dispatch dispatch, RunId runId, Fence dispatchFence, Duration timeout, Duration lockTtl) {
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.dispatchFence = Objects.requireNonNull(dispatchFence, "dispatchFence");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.lockTtl = Objects.requireNonNull(lockTtl, "lockTtl");
        if (timeout.isNegative() || timeout.isZero() || lockTtl.compareTo(timeout) <= 0) {
            throw new IllegalArgumentException("The lock TTL must exceed a positive call timeout");
        }
    }

    public Dispatch dispatch() {
        return dispatch;
    }

    public RunId runId() {
        return runId;
    }

    public Fence dispatchFence() {
        return dispatchFence;
    }

    public Duration timeout() {
        return timeout;
    }

    public Duration lockTtl() {
        return lockTtl;
    }

    public Optional<RunLock> lock() {
        return Optional.ofNullable(lock);
    }

    public Optional<Fence> claimedFence() {
        return Optional.ofNullable(claimedFence);
    }

    public Optional<Instant> leaseUntil() {
        return Optional.ofNullable(leaseUntil);
    }

    /** Records the successful lock acquisition. */
    public void acquired(RunLock ownedLock) {
        if (lock != null) {
            throw new IllegalStateException("The run lock was already acquired");
        }
        lock = Objects.requireNonNull(ownedLock, "ownedLock");
    }

    /** Records the fenced database claim and its lease. */
    public void claimed(Fence newFence, Instant newLeaseUntil) {
        if (lock == null || claimedFence != null) {
            throw new IllegalStateException("A run must be locked before it is claimed exactly once");
        }
        claimedFence = Objects.requireNonNull(newFence, "newFence");
        leaseUntil = Objects.requireNonNull(newLeaseUntil, "newLeaseUntil");
    }

    /** Refreshes the local lease timestamp after both Redis and PostgreSQL renew successfully. */
    public void renewed(Instant newLeaseUntil) {
        leaseUntil = Objects.requireNonNull(newLeaseUntil, "newLeaseUntil");
    }

    /** Marks the lease unsafe after a renewal or ownership check fails. */
    public void loseLease() {
        leaseLost.set(true);
    }

    public boolean leaseLost() {
        return leaseLost.get();
    }
}
