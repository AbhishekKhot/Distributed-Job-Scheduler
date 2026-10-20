package dev.kairos.domain.health;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * When the auto-suspend evaluator suspends an endpoint (LLD section 4.1).
 *
 * <p>An endpoint is suspended when, within one {@code window}, at least {@code deadLetterThreshold}
 * runs were dead-lettered, or at least {@code minAttempts} attempts were made and at least
 * {@code failurePercent} percent of them failed. Both thresholds use the same window. No endpoint
 * is suspended within {@code coolDown} of its last status change, so an endpoint a team member
 * just resumed gets time to recover. The caller skips endpoints whose {@code suspendPolicy} is
 * disabled.
 *
 * @param deadLetterThreshold dead letters in the window that suspend, at least 1
 * @param window the evaluation window, positive
 * @param failurePercent failure percentage that suspends, 1 to 100
 * @param minAttempts attempts needed before the failure percentage counts, at least 1
 * @param coolDown time after a status change during which no suspension happens, not negative
 */
public record EndpointSuspendRule(
        int deadLetterThreshold, Duration window, int failurePercent, int minAttempts, Duration coolDown) {

    /** The LLD defaults: 50 dead letters in 5 minutes, or 80% failures over 200 attempts; 10-minute cool-down. */
    public static final EndpointSuspendRule DEFAULT =
            new EndpointSuspendRule(50, Duration.ofMinutes(5), 80, 200, Duration.ofMinutes(10));

    private static final int HUNDRED = 100;

    /**
     * Validates the components.
     *
     * @throws IllegalArgumentException if a component is out of range
     * @throws NullPointerException if a duration is null
     */
    public EndpointSuspendRule {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(coolDown, "coolDown");
        if (deadLetterThreshold < 1 || minAttempts < 1) {
            throw new IllegalArgumentException("Thresholds are at least 1");
        }
        if (failurePercent < 1 || failurePercent > HUNDRED) {
            throw new IllegalArgumentException("The failure percentage lies between 1 and 100");
        }
        if (window.isNegative() || window.isZero() || coolDown.isNegative()) {
            throw new IllegalArgumentException("The window is positive and the cool-down not negative");
        }
    }

    /**
     * Evaluates one endpoint.
     *
     * @param counters the endpoint's counters over {@link #window()}
     * @param lastStatusChangeAt the endpoint's {@code status_changed_at}, or null if it never
     *     changed
     * @param now the evaluation time, from the caller's clock
     * @return keep, suspend, or cooling down
     */
    public SuspendDecision evaluate(EndpointHealthWindow counters, @Nullable Instant lastStatusChangeAt, Instant now) {
        Objects.requireNonNull(counters, "counters");
        Objects.requireNonNull(now, "now");
        SuspendDecision.Reason reason = trippedReason(counters);
        if (reason == null) {
            return new SuspendDecision.Keep();
        }
        if (lastStatusChangeAt != null) {
            Instant until = lastStatusChangeAt.plus(coolDown);
            if (now.isBefore(until)) {
                return new SuspendDecision.CoolingDown(reason, until);
            }
        }
        return new SuspendDecision.Suspend(reason);
    }

    private SuspendDecision.@Nullable Reason trippedReason(EndpointHealthWindow counters) {
        if (counters.deadLettered() >= deadLetterThreshold) {
            return SuspendDecision.Reason.DEAD_LETTERS;
        }
        // Integer arithmetic (failures * 100 >= percent * attempts) avoids rounding at the boundary.
        if (counters.attempts() >= minAttempts
                && (long) counters.failures() * HUNDRED >= (long) failurePercent * counters.attempts()) {
            return SuspendDecision.Reason.FAILURE_RATE;
        }
        return null;
    }
}
