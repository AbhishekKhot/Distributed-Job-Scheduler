package dev.kairos.engine.lock;

/** The result of {@link RunLockPort#extend}. */
public enum ExtendResult {
    /** The caller owned the lock and its TTL was reset to the requested value. */
    EXTENDED,
    /** The caller did not own the lock any more; nothing was changed. */
    NOT_OWNED,
    /** Redis failed or timed out; the caller must treat the lock as lost. */
    UNAVAILABLE
}
