package dev.kairos.engine.lock;

import dev.kairos.domain.id.RunId;
import java.util.Objects;

/**
 * A run lock this node owns: the handle {@link RunLockPort#acquire} returns and the other lock operations take.
 *
 * @param runId the locked run
 * @param token the owner token stored in the lock
 */
public record RunLock(RunId runId, LockToken token) {

    private static final String KEY_PREFIX = "kairos:lock:run:";

    /** Validates the parts. */
    public RunLock {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(token, "token");
    }

    /**
     * Returns the Redis key of a run's lock (LLD section 4.3).
     *
     * @param runId the run
     * @return {@code kairos:lock:run:{runId}}
     */
    public static String key(RunId runId) {
        return KEY_PREFIX + runId;
    }

    /**
     * Returns the Redis key of this lock.
     *
     * @return {@code kairos:lock:run:{runId}}
     */
    public String key() {
        return key(runId);
    }
}
