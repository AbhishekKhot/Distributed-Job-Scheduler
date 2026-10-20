package dev.kairos.domain.health;

/**
 * An endpoint's counters over the evaluation window, summed from {@code endpoint_health_minute}.
 *
 * @param attempts attempts that reached a classification, not negative
 * @param successes successful attempts, 0 to {@code attempts}
 * @param deadLettered runs dead-lettered, not negative
 */
public record EndpointHealthWindow(int attempts, int successes, int deadLettered) {

    /**
     * Validates the counters.
     *
     * @throws IllegalArgumentException if a counter is negative or successes exceed attempts
     */
    public EndpointHealthWindow {
        if (attempts < 0 || successes < 0 || deadLettered < 0) {
            throw new IllegalArgumentException("Counters cannot be negative");
        }
        if (successes > attempts) {
            throw new IllegalArgumentException("Successes cannot exceed attempts");
        }
    }

    /**
     * Returns the failed attempts, retryable or not.
     *
     * @return {@code attempts - successes}
     */
    public int failures() {
        return attempts - successes;
    }
}
