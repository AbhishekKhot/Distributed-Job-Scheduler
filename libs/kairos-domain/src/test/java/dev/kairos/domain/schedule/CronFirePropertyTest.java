package dev.kairos.domain.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.domain.model.DstPolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Cron iteration against a brute-force reference: every minute of a window around a DST
 * transition is checked against the expression and the policy.
 */
class CronFirePropertyTest {

    private static final List<String> ZONES = List.of(
            "America/New_York", "Europe/London", "Australia/Sydney", "Asia/Kolkata", "Australia/Lord_Howe", "UTC");

    private static final Duration WINDOW = Duration.ofDays(3);

    @Property(tries = 200)
    void fireTimesStrictlyIncreaseAndNeverSkipAValidOccurrence(
            @ForAll("expressions") String expression,
            @ForAll("zones") String zoneId,
            @ForAll DstPolicy policy,
            @ForAll @IntRange(min = 0, max = 3) int transitionIndex,
            @ForAll @IntRange(min = -2_880, max = 1_440) int offsetMinutes) {
        ZoneId zone = ZoneId.of(zoneId);
        ZoneRules rules = zone.getRules();
        Instant from = startNear(rules, transitionIndex).plus(Duration.ofMinutes(offsetMinutes));
        Instant to = from.plus(WINDOW);
        CronExpression cron = CronExpression.parse(expression);
        ScheduleCalculator calculator = new CronCalculator(cron, zone, policy);

        List<Instant> fired = new ArrayList<>();
        Fire fire = calculator.nextFire(from, null).orElseThrow();
        while (!fire.at().isAfter(to)) {
            fired.add(fire.at());
            fire = calculator.nextFire(fire.at(), fire.local()).orElseThrow();
        }

        for (int i = 1; i < fired.size(); i++) {
            assertThat(fired.get(i)).isAfter(fired.get(i - 1));
        }
        Set<Instant> expected = new HashSet<>();
        Set<Instant> shifted = new HashSet<>();
        Instant minute = from.truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1));
        for (; !minute.isAfter(to); minute = minute.plus(Duration.ofMinutes(1))) {
            LocalDateTime local = LocalDateTime.ofInstant(minute, zone);
            if (matches(expression, local) && isValidOccurrence(rules, local, minute, policy)) {
                expected.add(minute);
            }
        }
        for (ZoneOffsetTransition t = rules.nextTransition(from);
                t != null && !t.getInstant().isAfter(to);
                t = rules.nextTransition(t.getInstant())) {
            if (t.isGap()
                    && policy.gap() == DstPolicy.Gap.SHIFT_FORWARD
                    && t.getInstant().isAfter(from)) {
                for (LocalDateTime l = t.getDateTimeBefore(); l.isBefore(t.getDateTimeAfter()); l = l.plusMinutes(1)) {
                    if (matches(expression, l)) {
                        shifted.add(t.getInstant());
                    }
                }
            }
        }
        Set<Instant> allowed = new HashSet<>(expected);
        allowed.addAll(shifted);
        assertThat(fired).containsAll(expected).containsAll(shifted);
        assertThat(allowed).containsAll(fired);
    }

    /**
     * An independent matcher for the generated expressions ({@code *}, {@code *}{@code /k},
     * {@code a-b}, {@code a,b} and single values; day-of-month and month are always {@code *}).
     */
    private static boolean matches(String expression, LocalDateTime local) {
        String[] fields = expression.split(" ", -1);
        return field(fields[0], local.getMinute())
                && field(fields[1], local.getHour())
                && field(fields[4], local.getDayOfWeek().getValue() % 7);
    }

    private static boolean field(String field, int value) {
        if (field.equals("*")) {
            return true;
        }
        if (field.startsWith("*/")) {
            return value % Integer.parseInt(field.substring(2)) == 0;
        }
        if (field.contains("-")) {
            String[] range = field.split("-", -1);
            return value >= Integer.parseInt(range[0]) && value <= Integer.parseInt(range[1]);
        }
        for (String item : field.split(",", -1)) {
            if (Integer.parseInt(item) == value) {
                return true;
            }
        }
        return false;
    }

    /** A minute-aligned instant is an occurrence unless it is the second pass of an overlap under FIRST. */
    private static boolean isValidOccurrence(ZoneRules rules, LocalDateTime local, Instant instant, DstPolicy policy) {
        ZoneOffsetTransition transition = rules.getTransition(local);
        if (transition == null || transition.isGap()) {
            return true;
        }
        boolean secondPass = rules.getOffset(instant).equals(transition.getOffsetAfter());
        return !secondPass || policy.overlap() == DstPolicy.Overlap.BOTH;
    }

    private static Instant startNear(ZoneRules rules, int index) {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        ZoneOffsetTransition transition = null;
        for (int i = 0; i <= index; i++) {
            ZoneOffsetTransition next = rules.nextTransition(transition == null ? start : transition.getInstant());
            if (next == null) {
                return start.plus(Duration.ofDays(90L * index));
            }
            transition = next;
        }
        return transition.getInstant();
    }

    @Provide
    Arbitrary<String> zones() {
        return Arbitraries.of(ZONES);
    }

    @Provide
    Arbitrary<String> expressions() {
        Arbitrary<String> minute = Arbitraries.oneOf(
                Arbitraries.just("*"),
                Arbitraries.of(2, 5, 10, 15, 20, 30).map(step -> "*/" + step),
                Arbitraries.integers().between(0, 59).map(String::valueOf),
                Arbitraries.integers().between(0, 58).map(m -> m + "," + (m + 1)));
        Arbitrary<String> hour = Arbitraries.oneOf(
                Arbitraries.just("*"),
                Arbitraries.integers().between(0, 23).map(String::valueOf),
                Arbitraries.integers().between(0, 21).map(h -> h + "-" + (h + 2)),
                Arbitraries.of(2, 3, 6).map(step -> "*/" + step));
        Arbitrary<String> dayOfWeek = Arbitraries.oneOf(Arbitraries.just("*"), Arbitraries.just("0-4"));
        return Combinators.combine(minute, hour, dayOfWeek).as((m, h, d) -> m + " " + h + " * * " + d);
    }
}
