package dev.kairos.engine.lock;

/** The result of {@link RunLockPort#release}. */
public enum ReleaseResult {
    /** The caller owned the lock and deleted it. */
    RELEASED,
    /** The caller did not own the lock; nothing was changed. */
    NOT_OWNED,
    /** Redis failed or timed out; the lock, if still owned, expires with its TTL. */
    UNAVAILABLE
}
