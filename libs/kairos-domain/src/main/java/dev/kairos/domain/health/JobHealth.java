package dev.kairos.domain.health;

import dev.kairos.domain.model.Outcome;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Recent health of a job ({@code job_health}).
 *
 * <p>Only final run outcomes count: {@link Outcome#SUCCEEDED} records a 0 bit and
 * {@link Outcome#DEAD_LETTERED} a 1 bit; cancelled and skipped runs say nothing about the target
 * and leave the health unchanged. Bit 0 of {@code recentMask} is the newest outcome, and only the
 * low {@code recentCount} bits are used. A job is unhealthy when more than half of the last 10
 * outcomes failed (at least 6 failures), the "job failing most of its recent runs" alert
 * (HLD section 12).
 *
 * <p>Immutable; {@link #withOutcome} returns a new value.
 *
 * @param recentMask the last {@code recentCount} outcomes, 1 for failure, newest in bit 0
 * @param recentCount how many outcomes the mask holds, 0 to 10
 * @param consecutiveDead dead-lettered runs since the last success, not negative
 * @param unhealthySince when the job became unhealthy, or null while it is healthy
 */
public record JobHealth(
        int recentMask,
        int recentCount,
        int consecutiveDead,
        @Nullable Instant unhealthySince) {

    /** How many outcomes the mask keeps. */
    public static final int WINDOW = 10;

    /** Failures within the window at which a job is unhealthy (more than half of 10). */
    public static final int UNHEALTHY_FAILURES = 6;

    /** A job with no recorded outcomes. */
    public static final JobHealth EMPTY = new JobHealth(0, 0, 0, null);

    /** Largest value {@code consecutive_dead} (a {@code smallint}) can hold. */
    private static final int MAX_CONSECUTIVE = Short.MAX_VALUE;

    private static final int WINDOW_MASK = (1 << WINDOW) - 1;

    /**
     * Validates the components.
     *
     * @throws IllegalArgumentException if the count is outside 0 to 10, the mask has bits beyond
     *     the count, the consecutive count is outside the {@code smallint} range, or
     *     {@code unhealthySince} disagrees with the failures in the mask
     */
    public JobHealth {
        if (recentCount < 0 || recentCount > WINDOW) {
            throw new IllegalArgumentException("The recent count lies between 0 and 10");
        }
        if ((recentMask & ~((1 << recentCount) - 1)) != 0) {
            throw new IllegalArgumentException("The mask holds only recentCount outcomes");
        }
        if (consecutiveDead < 0 || consecutiveDead > MAX_CONSECUTIVE) {
            throw new IllegalArgumentException("The consecutive dead count is out of range");
        }
        if ((unhealthySince != null) != (Integer.bitCount(recentMask) >= UNHEALTHY_FAILURES)) {
            throw new IllegalArgumentException("unhealthySince is set exactly while the job is unhealthy");
        }
    }

    /**
     * Records a final run outcome.
     *
     * @param outcome the run's final outcome
     * @param at when it was settled, used if the job becomes unhealthy now
     * @return the new health; this instance for outcomes that do not count
     */
    public JobHealth withOutcome(Outcome outcome, Instant at) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(at, "at");
        boolean failed;
        if (outcome == Outcome.SUCCEEDED) {
            failed = false;
        } else if (outcome == Outcome.DEAD_LETTERED) {
            failed = true;
        } else {
            return this;
        }
        int mask = ((recentMask << 1) | (failed ? 1 : 0)) & WINDOW_MASK;
        int count = Math.min(WINDOW, recentCount + 1);
        int consecutive = failed ? Math.min(MAX_CONSECUTIVE, consecutiveDead + 1) : 0;
        boolean unhealthy = Integer.bitCount(mask) >= UNHEALTHY_FAILURES;
        Instant since = unhealthy ? (unhealthySince != null ? unhealthySince : at) : null;
        return new JobHealth(mask, count, consecutive, since);
    }

    /**
     * Returns the failures among the recorded outcomes.
     *
     * @return 0 to 10
     */
    public int recentFailures() {
        return Integer.bitCount(recentMask);
    }

    /**
     * Tells whether most of the last 10 outcomes failed.
     *
     * @return true with at least {@link #UNHEALTHY_FAILURES} failures in the window
     */
    public boolean isUnhealthy() {
        return unhealthySince != null;
    }

    /**
     * Returns the health after a team member resumes an auto-paused job: the dead-letter streak
     * restarts, so the job is not paused again by its next failure alone; the outcome history is
     * kept.
     *
     * @return the health with {@code consecutiveDead} set to 0
     */
    public JobHealth afterResume() {
        return new JobHealth(recentMask, recentCount, 0, unhealthySince);
    }
}
