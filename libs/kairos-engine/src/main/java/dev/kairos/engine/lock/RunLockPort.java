package dev.kairos.engine.lock;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.run.Fence;
import java.time.Duration;

/**
 * The Redis run lock {@code kairos:lock:run:{runId}} (HLD section 6, gate 1; LLD section 4.3).
 *
 * <p>The executor takes the lock before it claims a run, checks the time left just before sending (gate 3, the
 * send-time guard), extends it for long calls and releases it after publishing the result. The lease sweeper inspects
 * it before recovering a run.
 *
 * <p>Every operation fails closed: a Redis error, a rejected command or a command timeout is returned as an
 * {@code UNAVAILABLE} result, never thrown and never reported as success.
 *
 * <p>Implementations are thread-safe.
 */
public interface RunLockPort {

    /** Longest TTL accepted by {@link #acquire} and {@link #extend}. */
    Duration MAX_TTL = Duration.ofHours(1);

    /**
     * Takes a run's lock if nobody holds it ({@code SET key token NX PX ttl}).
     *
     * @param runId the run
     * @param fence the fence of the dispatch being executed, written into the owner token
     * @param ttl how long the lock lives unless released or extended: the call timeout plus 10s (LLD section 4.1)
     * @return {@link AcquireResult.Acquired} with the lock handle, {@link AcquireResult.Held} if another owner holds it,
     *     or {@link AcquireResult.Unavailable} if Redis failed or timed out
     * @throws IllegalArgumentException if the TTL is not positive or longer than {@link #MAX_TTL}
     */
    AcquireResult acquire(RunId runId, Fence fence, Duration ttl);

    /**
     * Returns how long the caller still owns a lock it acquired.
     *
     * @param lock the handle returned by {@link #acquire}
     * @return {@link RemainingResult.Owned} with the TTL left, {@link RemainingResult.NotOwned} if the lock expired,
     *     was released or belongs to someone else, or {@link RemainingResult.Unavailable}
     */
    RemainingResult remaining(RunLock lock);

    /**
     * Deletes the lock if the caller still owns it (compare-and-delete). A caller that does not own it changes nothing.
     *
     * @param lock the handle returned by {@link #acquire}
     * @return whether it was released
     */
    ReleaseResult release(RunLock lock);

    /**
     * Resets the lock's TTL if the caller still owns it (compare-and-extend), for calls that run long.
     *
     * @param lock the handle returned by {@link #acquire}
     * @param ttl the new time to live, counted from now
     * @return whether it was extended
     * @throws IllegalArgumentException if the TTL is not positive or longer than {@link #MAX_TTL}
     */
    ExtendResult extend(RunLock lock, Duration ttl);

    /**
     * Reads who holds a run's lock, without changing it.
     *
     * @param runId the run
     * @return the holder, {@link LockInspection.Free} or {@link LockInspection.Unavailable}
     */
    LockInspection inspect(RunId runId);
}
