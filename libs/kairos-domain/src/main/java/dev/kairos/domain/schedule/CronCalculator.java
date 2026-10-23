package dev.kairos.domain.schedule;

import dev.kairos.domain.model.DstPolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code CRON}: local times matching the expression, mapped to instants in the schedule's zone
 * under its {@link DstPolicy}.
 *
 * <p>Matching happens on wall-clock times only. Each matching local time then maps to zero, one or
 * two instants:
 *
 * <ul>
 *   <li>a normal local time maps to its single instant;
 *   <li>a local time in a gap (clocks jump forward over it) maps to the transition instant, the
 *       first valid instant after the gap, under {@link DstPolicy.Gap#SHIFT_FORWARD}, or to
 *       nothing under {@link DstPolicy.Gap#SKIP}. Several local times in one gap shift to the same
 *       instant and fire once;
 *   <li>a local time in an overlap (clocks fall back, so it occurs twice) maps to its earlier
 *       instant under {@link DstPolicy.Overlap#FIRST}, or to both under
 *       {@link DstPolicy.Overlap#BOTH}.
 * </ul>
 *
 * <p>The local time of the last fire ({@code last_fired_local}) keeps iteration moving forward in
 * local time: a local time before it is never returned, and the same local time is returned again
 * only for the second instant of an overlap under {@code BOTH}. This stops a second fire of
 * {@code 30 1 * * *} on the day clocks fall back even if the caller's {@code after} is from the
 * first pass through the repeated hour.
 */
final class CronCalculator implements ScheduleCalculator {

    /** Most consecutive matches skipped (in a gap or before {@code after}) before giving up. */
    static final int MAX_CANDIDATES = 100_000;

    /** Matches sampled to find the period. */
    private static final int PERIOD_SAMPLES = 64;

    private static final LocalDateTime PERIOD_PROBE = LocalDateTime.of(2001, 1, 1, 0, 0);

    private final CronExpression expression;
    private final ZoneId zone;
    private final ZoneRules rules;
    private final DstPolicy policy;
    private final Duration period;

    CronCalculator(CronExpression expression, ZoneId zone, DstPolicy policy) {
        this.expression = Objects.requireNonNull(expression, "expression");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.rules = zone.getRules();
        this.period = shortestGap(expression);
    }

    @Override
    public Optional<Fire> first(Instant now) {
        return nextFire(now, null);
    }

    @Override
    public Optional<Fire> nextFire(Instant after, @Nullable LocalDateTime lastFiredLocal) {
        Objects.requireNonNull(after, "after");
        LocalDateTime cursor = searchStart(after, lastFiredLocal);
        Fire best = null;
        boolean settled = false;
        for (int i = 0; i < MAX_CANDIDATES && !settled; i++) {
            Optional<LocalDateTime> match = expression.nextMatchAfter(cursor);
            if (match.isEmpty()) {
                return Optional.ofNullable(best);
            }
            LocalDateTime local = match.get();
            // The earliest instant of a local time never decreases as the local time grows, so no
            // later match can beat the best fire once its earliest instant is not before it.
            if (best != null && !earliestInstant(local).isBefore(best.at())) {
                settled = true;
                continue;
            }
            for (Instant at : eligibleInstants(local, after, lastFiredLocal)) {
                if (best == null || at.isBefore(best.at())) {
                    best = new Fire(at, local);
                }
            }
            cursor = local;
        }
        if (best == null) {
            throw new IllegalStateException("No fire time found within " + MAX_CANDIDATES + " cron matches");
        }
        return Optional.of(best);
    }

    /**
     * Returns an exclusive local lower bound below every local time that can still yield an
     * instant after {@code after}.
     */
    private LocalDateTime searchStart(Instant after, @Nullable LocalDateTime lastFiredLocal) {
        LocalDateTime start = LocalDateTime.ofInstant(after, zone);
        ZoneOffsetTransition next = rules.nextTransition(after);
        if (next != null && next.isOverlap() && !start.isBefore(next.getDateTimeAfter())) {
            // after is in the first pass through a repeated range: the second instants of the
            // local times already passed are still ahead.
            start = next.getDateTimeAfter().minusMinutes(1);
        }
        if (lastFiredLocal != null && policy.overlap() == DstPolicy.Overlap.FIRST) {
            // Under FIRST every local time fires at most once, so nothing at or before the last
            // fired local time is eligible. (Under BOTH a second-pass instant may have an earlier
            // local time than the last fire.)
            LocalDateTime fromLast = lastFiredLocal.minusMinutes(1);
            if (fromLast.isAfter(start)) {
                start = fromLast;
            }
        }
        return start;
    }

    /** The earliest instant a local time can stand for, whatever the policy. */
    private Instant earliestInstant(LocalDateTime local) {
        ZoneOffsetTransition transition = rules.getTransition(local);
        if (transition == null) {
            return local.toInstant(rules.getValidOffsets(local).get(0));
        }
        return transition.isGap() ? transition.getInstant() : local.toInstant(transition.getOffsetBefore());
    }

    /**
     * Returns the instants of a matching local time that are after {@code after} and not
     * suppressed by the last fired local time.
     *
     * <p>A first-pass instant (the only instant, a gap shift, or the earlier instant of an
     * overlap) is suppressed when its local time is at or before {@code lastFiredLocal}. The
     * second instant of an overlap under {@code BOTH} is limited by {@code after} only.
     */
    private List<Instant> eligibleInstants(LocalDateTime local, Instant after, @Nullable LocalDateTime lastFiredLocal) {
        boolean firstPassAllowed = lastFiredLocal == null || local.isAfter(lastFiredLocal);
        ZoneOffsetTransition transition = rules.getTransition(local);
        Instant first;
        Instant second = null;
        if (transition == null) {
            first = local.toInstant(rules.getValidOffsets(local).get(0));
        } else if (transition.isGap()) {
            if (policy.gap() == DstPolicy.Gap.SKIP) {
                return List.of();
            }
            first = transition.getInstant();
        } else {
            first = local.toInstant(transition.getOffsetBefore());
            if (policy.overlap() == DstPolicy.Overlap.BOTH) {
                second = local.toInstant(transition.getOffsetAfter());
            }
        }
        List<Instant> instants = new ArrayList<>(2);
        if (firstPassAllowed && first.isAfter(after)) {
            instants.add(first);
        }
        if (second != null && second.isAfter(after)) {
            instants.add(second);
        }
        return instants;
    }

    @Override
    public Fire fireAt(Instant at) {
        Objects.requireNonNull(at, "at");
        return new Fire(at, LocalDateTime.ofInstant(at, zone));
    }

    @Override
    public Optional<Duration> period() {
        return Optional.of(period);
    }

    /**
     * The shortest wall-clock gap between consecutive matches, sampled from a fixed date so it is
     * the same for every caller and every day.
     */
    private static Duration shortestGap(CronExpression expression) {
        Duration shortest = null;
        LocalDateTime previous = expression.nextMatchAfter(PERIOD_PROBE).orElseThrow();
        for (int i = 0; i < PERIOD_SAMPLES; i++) {
            Optional<LocalDateTime> next = expression.nextMatchAfter(previous);
            if (next.isEmpty()) {
                break;
            }
            Duration gap = Duration.between(previous, next.get());
            if (shortest == null || gap.compareTo(shortest) < 0) {
                shortest = gap;
            }
            previous = next.get();
        }
        return shortest == null ? Duration.ofDays(366) : shortest;
    }
}
