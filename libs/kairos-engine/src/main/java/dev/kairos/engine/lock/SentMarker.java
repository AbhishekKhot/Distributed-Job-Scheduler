package dev.kairos.engine.lock;

import dev.kairos.domain.id.RunId;
import java.util.Objects;

/**
 * Identifies one sent marker: a run, its attempt and its infrastructure attempt (HLD section 6, gate 4).
 *
 * @param runId the run
 * @param attempt the attempt, from 1
 * @param infraAttempt the re-queues after executor loss for this attempt, from 0
 */
public record SentMarker(RunId runId, int attempt, int infraAttempt) {

    /** Largest attempt or infrastructure attempt ({@code smallint} columns). */
    public static final int MAX_ATTEMPT = Short.MAX_VALUE;

    private static final String KEY_PREFIX = "kairos:sent:";

    /**
     * Validates the parts.
     *
     * @throws IllegalArgumentException if the attempt is not 1 to 32767 or the infrastructure attempt not 0 to 32767
     */
    public SentMarker {
        Objects.requireNonNull(runId, "runId");
        if (attempt < 1 || attempt > MAX_ATTEMPT) {
            throw new IllegalArgumentException("An attempt is 1 to " + MAX_ATTEMPT);
        }
        if (infraAttempt < 0 || infraAttempt > MAX_ATTEMPT) {
            throw new IllegalArgumentException("An infrastructure attempt is 0 to " + MAX_ATTEMPT);
        }
    }

    /**
     * Returns the Redis key (LLD section 4.3).
     *
     * @return {@code kairos:sent:{runId}:{attempt}:{infraAttempt}}
     */
    public String key() {
        return KEY_PREFIX + runId + ":" + attempt + ":" + infraAttempt;
    }
}
