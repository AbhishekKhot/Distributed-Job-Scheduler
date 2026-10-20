package dev.kairos.engine.lock;

import java.time.Duration;

/**
 * Sent markers {@code kairos:sent:{runId}:{attempt}:{infraAttempt}} (HLD section 6, gate 4; LLD section 4.3).
 *
 * <p>The executor writes the marker just before the request leaves; if the write fails, it does not send. The lease
 * sweeper reads it for a run whose executor died: without a marker the run is safely re-queued, with one the outcome
 * is unknown and the endpoint's {@code unknownOutcomePolicy} decides.
 *
 * <p>Every operation fails closed: a Redis error or timeout is returned as {@code UNAVAILABLE}, never thrown.
 *
 * <p>Implementations are thread-safe.
 */
public interface SentMarkerPort {

    /** How long a marker lives: 48h (LLD section 4.3). */
    Duration TTL = Duration.ofDays(2);

    /**
     * Writes a marker with a 48h TTL. Writing it again resets the TTL.
     *
     * @param marker the run, attempt and infrastructure attempt about to be sent
     * @return {@link MarkResult#MARKED}, or {@link MarkResult#UNAVAILABLE}, in which case the caller must not send
     */
    MarkResult mark(SentMarker marker);

    /**
     * Tells whether a marker exists.
     *
     * @param marker the run, attempt and infrastructure attempt to check
     * @return present, absent, or unavailable
     */
    MarkerCheck check(SentMarker marker);

    /** The result of {@link #mark}. */
    enum MarkResult {
        /** The marker is written. */
        MARKED,
        /** Redis failed or timed out; the marker may not exist. */
        UNAVAILABLE
    }

    /** The result of {@link #check}. */
    enum MarkerCheck {
        /** The marker exists: the request may have reached the target. */
        PRESENT,
        /** No marker: the request was never sent (unless Redis lost it in a failover; see T-225). */
        ABSENT,
        /** Redis failed or timed out. */
        UNAVAILABLE
    }
}
