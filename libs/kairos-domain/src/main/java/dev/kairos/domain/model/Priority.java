package dev.kairos.domain.model;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;

/**
 * Priority of a job or workflow, 1 (lowest) to 10 (highest).
 *
 * @param value the priority, 1 to 10
 */
public record Priority(int value) implements Comparable<Priority> {

    /** Lowest priority. */
    public static final int MIN = 1;

    /** Highest priority. */
    public static final int MAX = 10;

    private static final int HIGH_FROM = 8;
    private static final int MEDIUM_FROM = 5;

    /**
     * Validates the value.
     *
     * @throws ValidationFailedException on field {@code priority} with code {@link FieldError#RANGE}
     *     if {@code value} is outside 1 to 10
     */
    public Priority {
        if (value < MIN || value > MAX) {
            throw ValidationFailedException.of("priority", FieldError.RANGE);
        }
    }

    /**
     * Returns the priority with the given value.
     *
     * @param value the priority, 1 to 10
     * @return the priority
     * @throws ValidationFailedException if {@code value} is outside 1 to 10
     */
    public static Priority of(int value) {
        return new Priority(value);
    }

    /**
     * Returns the dispatch tier: 8 to 10 high, 5 to 7 medium, 1 to 4 low.
     *
     * @return the tier
     */
    public PriorityTier tier() {
        if (value >= HIGH_FROM) {
            return PriorityTier.HIGH;
        }
        return value >= MEDIUM_FROM ? PriorityTier.MEDIUM : PriorityTier.LOW;
    }

    @Override
    public int compareTo(Priority other) {
        return Integer.compare(value, other.value);
    }
}
