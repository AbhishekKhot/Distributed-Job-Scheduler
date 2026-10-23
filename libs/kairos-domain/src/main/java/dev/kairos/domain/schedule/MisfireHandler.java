package dev.kairos.domain.schedule;

import dev.kairos.domain.model.MisfirePolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Decides which occurrences of a due schedule to create (LLD section 4, fire poller).
 *
 * <p>An occurrence is <em>on time</em> when it is at most the threshold (60 seconds by default)
 * before {@code now}, and <em>misfired</em> when it is older. Every on-time occurrence up to the
 * poller's horizon ({@code now} plus its look-ahead) is created. Misfired occurrences follow the
 * schedule's {@link MisfirePolicy}:
 *
 * <ul>
 *   <li>{@code RUN_ALL}: the most recent {@code maxCatchup} misfired occurrences are created; older
 *       ones are dropped;
 *   <li>{@code RUN_LATEST} (the default): only the most recent occurrence runs, so the latest
 *       misfired occurrence is created only if no occurrence falls in {@code [now - threshold, now]};
 *   <li>{@code SKIP}: none are created.
 * </ul>
 *
 * <p>Misfired occurrences are never enumerated one by one from an old {@code next_fire_at}: the
 * handler jumps to the threshold and looks back in doubling windows, so a schedule that was
 * paused for days costs about as much as one that was a minute late.
 */
public final class MisfireHandler {

    /** Lateness above which an occurrence is a misfire (LLD section 4, fire poller). */
    public static final Duration DEFAULT_THRESHOLD = Duration.ofSeconds(60);

    /** Largest {@code maxCatchup} accepted. */
    public static final int MAX_CATCHUP = 1_000;

    /** Most on-time occurrences created by one plan; the rest are left for the next poll. */
    public static final int MAX_ON_TIME_FIRES = 1_000;

    private static final Duration FIRST_LOOK_BACK = Duration.ofMinutes(1);

    private final Duration threshold;

    /**
     * Creates a handler.
     *
     * @param threshold the misfire threshold, positive
     * @throws IllegalArgumentException if the threshold is not positive
     */
    public MisfireHandler(Duration threshold) {
        this.threshold = Objects.requireNonNull(threshold, "threshold");
        if (threshold.isNegative() || threshold.isZero()) {
            throw new IllegalArgumentException("The misfire threshold must be positive");
        }
    }

    /**
     * Returns a handler with the {@link #DEFAULT_THRESHOLD}.
     *
     * @return the handler
     */
    public static MisfireHandler standard() {
        return new MisfireHandler(DEFAULT_THRESHOLD);
    }

    /**
     * Plans the occurrences of a due schedule.
     *
     * @param calculator the schedule's calculator
     * @param nextFireAt the stored {@code next_fire_at}
     * @param lastFiredLocal the stored {@code last_fired_local}, or null
     * @param now the current time
     * @param horizon the latest occurrence to create now ({@code now} plus the look-ahead), not
     *     before {@code now}
     * @param policy the misfire policy
     * @param maxCatchup the cap on misfired occurrences under {@code RUN_ALL}, 0 to
     *     {@link #MAX_CATCHUP}
     * @return the plan
     * @throws IllegalArgumentException if {@code horizon} is before {@code now} or
     *     {@code maxCatchup} is out of range
     */
    public FirePlan plan(
            ScheduleCalculator calculator,
            Instant nextFireAt,
            @Nullable LocalDateTime lastFiredLocal,
            Instant now,
            Instant horizon,
            MisfirePolicy policy,
            int maxCatchup) {
        Objects.requireNonNull(calculator, "calculator");
        Objects.requireNonNull(nextFireAt, "nextFireAt");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(horizon, "horizon");
        Objects.requireNonNull(policy, "policy");
        if (horizon.isBefore(now)) {
            throw new IllegalArgumentException("The horizon cannot be before now");
        }
        if (maxCatchup < 0 || maxCatchup > MAX_CATCHUP) {
            throw new IllegalArgumentException("maxCatchup must be 0 to " + MAX_CATCHUP);
        }
        Fire due = calculator.fireAt(nextFireAt);
        if (due.at().isAfter(horizon)) {
            return new FirePlan(List.of(), due, lastFiredLocal, false);
        }
        Instant onTimeFrom = now.minus(threshold);
        List<Fire> fires = new ArrayList<>();
        Optional<Fire> cursor = Optional.of(due);
        boolean misfired = due.at().isBefore(onTimeFrom);
        if (misfired) {
            Optional<Fire> firstOnTime = calculator.nextFire(onTimeFrom.minusNanos(1), lastFiredLocal);
            boolean onTimeExists =
                    firstOnTime.isPresent() && !firstOnTime.get().at().isAfter(now);
            int keep = switch (policy) {
                case RUN_ALL -> maxCatchup;
                case RUN_LATEST -> onTimeExists ? 0 : 1;
                case SKIP -> 0;
            };
            if (keep > 0) {
                fires.addAll(latestBefore(calculator, due, onTimeFrom, keep));
            }
            cursor = firstOnTime;
        }
        int onTime = 0;
        while (cursor.isPresent() && !cursor.get().at().isAfter(horizon) && onTime < MAX_ON_TIME_FIRES) {
            Fire fire = cursor.get();
            fires.add(fire);
            onTime++;
            cursor = calculator.nextFire(fire.at(), fire.local());
        }
        LocalDateTime lastLocal =
                fires.isEmpty() ? lastFiredLocal : fires.getLast().local();
        return new FirePlan(fires, cursor.orElse(null), lastLocal, misfired);
    }

    /**
     * Returns up to {@code count} of the latest occurrences in {@code [due, end)}, oldest first,
     * looking back from {@code end} in doubling windows.
     */
    private static List<Fire> latestBefore(ScheduleCalculator calculator, Fire due, Instant end, int count) {
        Duration lookBack = FIRST_LOOK_BACK;
        while (true) {
            Instant windowStart = end.minus(lookBack);
            boolean reachesDue = !windowStart.isAfter(due.at());
            Optional<Fire> cursor =
                    reachesDue ? Optional.of(due) : calculator.nextFire(windowStart.minusNanos(1), null);
            Deque<Fire> latest = new ArrayDeque<>(count + 1);
            int seen = 0;
            while (cursor.isPresent() && cursor.get().at().isBefore(end)) {
                Fire fire = cursor.get();
                latest.addLast(fire);
                if (latest.size() > count) {
                    latest.removeFirst();
                }
                seen++;
                cursor = calculator.nextFire(fire.at(), fire.local());
            }
            if (seen >= count || reachesDue) {
                return List.copyOf(latest);
            }
            lookBack = lookBack.multipliedBy(2);
        }
    }
}
