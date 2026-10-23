package dev.kairos.domain.schedule;

import dev.kairos.domain.model.DstPolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Computes the occurrences of one schedule (strategy per {@link ScheduleSpec} kind).
 *
 * <p>Every method is a pure function of its arguments: the current time is passed in, never read
 * from a clock. Implementations are immutable and thread-safe.
 */
public sealed interface ScheduleCalculator
        permits ImmediateCalculator, OneTimeCalculator, CronCalculator, IntervalCalculator {

    /** Most fire times a preview returns. */
    int MAX_PREVIEW = 10;

    /**
     * Returns the calculator for a schedule.
     *
     * @param spec the schedule
     * @param dstPolicy the daylight-saving policy ({@code schedules.dst_policy}); used by cron
     *     schedules only
     * @param intervalAnchor an instant that is an occurrence of an interval schedule: its creation
     *     time for a new schedule, or its stored {@code next_fire_at}; ignored by other kinds
     * @return the calculator
     */
    static ScheduleCalculator of(ScheduleSpec spec, DstPolicy dstPolicy, Instant intervalAnchor) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(dstPolicy, "dstPolicy");
        Objects.requireNonNull(intervalAnchor, "intervalAnchor");
        return switch (spec) {
            case ScheduleSpec.Immediate immediate -> ImmediateCalculator.INSTANCE;
            case ScheduleSpec.OneTime oneTime -> new OneTimeCalculator(oneTime.at());
            case ScheduleSpec.Cron cron -> new CronCalculator(cron.expression(), cron.zone(), dstPolicy);
            case ScheduleSpec.Interval interval -> new IntervalCalculator(interval.every(), intervalAnchor);
        };
    }

    /**
     * Returns the first occurrence of a schedule created (or recomputed) at {@code now}: the
     * instant of a one-time schedule even if it is slightly in the past, otherwise the first
     * occurrence strictly after {@code now}.
     *
     * @param now the current time
     * @return the first occurrence, or empty if the schedule never fires
     */
    Optional<Fire> first(Instant now);

    /**
     * Returns the next occurrence strictly after {@code after}.
     *
     * @param after the exclusive lower bound, normally the occurrence that just fired
     * @param lastFiredLocal the local time of the last fired occurrence
     *     ({@code schedules.last_fired_local}), or null if the schedule never fired; a cron
     *     schedule never returns a local time before it, nor the same local time again unless the
     *     DST policy runs both occurrences of an overlap
     * @return the next occurrence, or empty if there is none
     */
    Optional<Fire> nextFire(Instant after, @Nullable LocalDateTime lastFiredLocal);

    /**
     * Returns the occurrence for a stored {@code next_fire_at}.
     *
     * @param at an instant this calculator produced
     * @return the occurrence with the local time it stands for
     */
    Fire fireAt(Instant at);

    /**
     * Returns the shortest gap between consecutive occurrences, used to bound the spread.
     *
     * @return the period, or empty for schedules that fire at most once
     */
    Optional<Duration> period();

    /**
     * Returns the next fire times of a schedule created at {@code now} (the API preview).
     *
     * @param now the current time
     * @param count how many occurrences, 1 to {@link #MAX_PREVIEW}
     * @return up to {@code count} occurrences in increasing order, fewer if the schedule ends
     * @throws IllegalArgumentException if {@code count} is outside 1 to 10
     */
    default List<Fire> preview(Instant now, int count) {
        Objects.requireNonNull(now, "now");
        if (count < 1 || count > MAX_PREVIEW) {
            throw new IllegalArgumentException("A preview has 1 to " + MAX_PREVIEW + " fire times");
        }
        List<Fire> fires = new ArrayList<>(count);
        Optional<Fire> next = first(now);
        while (next.isPresent() && fires.size() < count) {
            Fire fire = next.get();
            fires.add(fire);
            next = nextFire(fire.at(), fire.local());
        }
        return List.copyOf(fires);
    }
}
