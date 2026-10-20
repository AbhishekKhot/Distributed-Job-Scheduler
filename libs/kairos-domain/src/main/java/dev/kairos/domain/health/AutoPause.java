package dev.kairos.domain.health;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.Outcome;
import java.util.Objects;

/**
 * A job's auto-pause setting ({@code autoPauseAfter} in the job spec).
 *
 * <p>The job is paused ({@code AUTO_PAUSED}) when a run is dead-lettered and that makes
 * {@code after} dead-lettered runs in a row. The caller applies it to recurring jobs only.
 *
 * @param after consecutive dead-lettered runs that pause the job, 0 to 100; 0 disables auto-pause
 */
public record AutoPause(int after) {

    /** Largest allowed threshold. */
    public static final int MAX = 100;

    /** The default: pause after 3 consecutive dead-lettered runs. */
    public static final AutoPause DEFAULT = new AutoPause(3);

    /** Auto-pause turned off. */
    public static final AutoPause DISABLED = new AutoPause(0);

    /**
     * Validates the threshold.
     *
     * @throws ValidationFailedException on field {@code autoPauseAfter} with code
     *     {@link FieldError#RANGE} if it is outside 0 to 100
     */
    public AutoPause {
        if (after < 0 || after > MAX) {
            throw ValidationFailedException.of("autoPauseAfter", FieldError.RANGE);
        }
    }

    /**
     * Tells whether auto-pause is on.
     *
     * @return true unless the threshold is 0
     */
    public boolean isEnabled() {
        return after > 0;
    }

    /**
     * Tells whether the job must be paused after recording an outcome.
     *
     * @param recorded the final outcome just recorded
     * @param health the health after recording it
     * @return true if auto-pause is on, the outcome is {@link Outcome#DEAD_LETTERED} and the
     *     dead-letter streak has reached the threshold
     */
    public boolean shouldPause(Outcome recorded, JobHealth health) {
        Objects.requireNonNull(recorded, "recorded");
        Objects.requireNonNull(health, "health");
        return isEnabled() && recorded == Outcome.DEAD_LETTERED && health.consecutiveDead() >= after;
    }
}
