package dev.kairos.watcher.outbox;

import java.time.Instant;

/** Optional token bucket shared by this watcher's shard polling threads. */
public final class RelayRateLimiter {
    private final int limit;
    private double tokens;
    private long lastNanos = Long.MIN_VALUE;

    /** Sets the maximum records per second; zero disables the cap. */
    public RelayRateLimiter(int limit) {
        if (limit < 0) throw new IllegalArgumentException("limit must not be negative");
        this.limit = limit;
        this.tokens = limit;
    }

    /** Reserves up to the requested number of record slots. */
    public synchronized int reserve(int requested, Instant now) {
        if (requested < 0) throw new IllegalArgumentException("requested must not be negative");
        if (limit == 0) return requested;
        long nanos = now.getEpochSecond() * 1_000_000_000L + now.getNano();
        if (lastNanos != Long.MIN_VALUE) {
            long elapsed = Math.max(0, nanos - lastNanos);
            tokens = Math.min(limit, tokens + elapsed * (double) limit / 1_000_000_000d);
        }
        lastNanos = nanos;
        int reserved = Math.min(requested, (int) tokens);
        tokens -= reserved;
        return reserved;
    }

    /** Returns slots reserved for rows that were absent from a claimed batch. */
    public synchronized void refund(int unused) {
        if (unused < 0) throw new IllegalArgumentException("unused must not be negative");
        if (limit != 0) tokens = Math.min(limit, tokens + unused);
    }
}
