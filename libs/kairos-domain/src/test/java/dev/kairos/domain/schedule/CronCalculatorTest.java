package dev.kairos.domain.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.domain.model.DstPolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CronCalculatorTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");
    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final ZoneId LORD_HOWE = ZoneId.of("Australia/Lord_Howe");

    static CronCalculator calculator(String expression, ZoneId zone, DstPolicy policy) {
        return new CronCalculator(CronExpression.parse(expression), zone, policy);
    }

    static CronCalculator calculator(String expression, ZoneId zone) {
        return calculator(expression, zone, DstPolicy.DEFAULT);
    }

    /** Fires strictly after {@code from}, feeding each fire back as {@code after} and {@code lastFiredLocal}. */
    static List<Fire> fires(ScheduleCalculator calculator, Instant from, int count) {
        List<Fire> fires = new ArrayList<>();
        Fire fire = calculator.nextFire(from, null).orElseThrow();
        fires.add(fire);
        while (fires.size() < count) {
            fire = calculator.nextFire(fire.at(), fire.local()).orElseThrow();
            fires.add(fire);
        }
        return fires;
    }

    static List<Instant> instants(List<Fire> fires) {
        return fires.stream().map(Fire::at).toList();
    }

    static Instant at(String text) {
        return Instant.parse(text);
    }

    @Nested
    class NewYork {

        @Test
        void twoAmOnDstStartDayRunsAtTheFirstValidInstantAfterTheGap() {
            List<Fire> fires = fires(calculator("0 2 * * *", NEW_YORK), at("2026-03-07T00:00:00Z"), 3);

            assertThat(instants(fires))
                    .containsExactly(
                            at("2026-03-07T07:00:00Z"), // 02:00 EST
                            at("2026-03-08T07:00:00Z"), // 02:00 does not exist: 03:00 EDT
                            at("2026-03-09T06:00:00Z")); // 02:00 EDT
            assertThat(fires.get(1).local()).isEqualTo(LocalDateTime.of(2026, 3, 8, 2, 0));
        }

        @Test
        void twoAmOnDstStartDayIsSkippedUnderTheSkipPolicy() {
            List<Fire> fires = fires(
                    calculator("0 2 * * *", NEW_YORK, DstPolicy.GAP_SKIP_OVERLAP_FIRST), at("2026-03-07T00:00:00Z"), 2);

            assertThat(instants(fires)).containsExactly(at("2026-03-07T07:00:00Z"), at("2026-03-09T06:00:00Z"));
        }

        @Test
        void halfPastOneOnDstEndDayFiresOnce() {
            List<Fire> fires = fires(calculator("30 1 * * *", NEW_YORK), at("2026-10-31T00:00:00Z"), 3);

            assertThat(instants(fires))
                    .containsExactly(
                            at("2026-10-31T05:30:00Z"), // 01:30 EDT
                            at("2026-11-01T05:30:00Z"), // first 01:30 (EDT) only
                            at("2026-11-02T06:30:00Z")); // 01:30 EST
        }

        @Test
        void halfPastOneOnDstEndDayFiresTwiceUnderTheBothPolicy() {
            List<Fire> fires = fires(
                    calculator("30 1 * * *", NEW_YORK, DstPolicy.GAP_SHIFT_FORWARD_OVERLAP_BOTH),
                    at("2026-10-31T12:00:00Z"),
                    3);

            assertThat(instants(fires))
                    .containsExactly(
                            at("2026-11-01T05:30:00Z"), at("2026-11-01T06:30:00Z"), at("2026-11-02T06:30:00Z"));
            assertThat(fires.get(0).local()).isEqualTo(fires.get(1).local());
        }

        @Test
        void bothPolicyInterleavesSeveralTimesInTheRepeatedHourInInstantOrder() {
            List<Fire> fires = fires(
                    calculator("*/30 1 * * *", NEW_YORK, DstPolicy.GAP_SHIFT_FORWARD_OVERLAP_BOTH),
                    at("2026-11-01T04:00:00Z"),
                    5);

            assertThat(instants(fires))
                    .containsExactly(
                            at("2026-11-01T05:00:00Z"),
                            at("2026-11-01T05:30:00Z"),
                            at("2026-11-01T06:00:00Z"),
                            at("2026-11-01T06:30:00Z"),
                            at("2026-11-02T06:00:00Z"));
        }

        @Test
        void lastFiredLocalPreventsASecondFireWhenAfterIsFromTheFirstPass() {
            CronCalculator calculator = calculator("30 1 * * *", NEW_YORK);
            LocalDateTime fired = LocalDateTime.of(2026, 11, 1, 1, 30);

            // 01:45 EDT: the second 01:30 (EST) is still ahead, but that local time already fired.
            assertThat(calculator.nextFire(at("2026-11-01T05:45:00Z"), fired))
                    .map(Fire::at)
                    .contains(at("2026-11-02T06:30:00Z"));
            // Even a stale "after" from before the fire does not fire it again.
            assertThat(calculator.nextFire(at("2026-11-01T05:00:00Z"), fired))
                    .map(Fire::at)
                    .contains(at("2026-11-02T06:30:00Z"));
            // Without the guard the stale "after" would give the same occurrence again.
            assertThat(calculator.nextFire(at("2026-11-01T05:00:00Z"), null))
                    .map(Fire::at)
                    .contains(at("2026-11-01T05:30:00Z"));
        }

        @Test
        void severalTimesInOneGapShiftToOneFire() {
            List<Fire> fires = fires(calculator("*/15 2 * * *", NEW_YORK), at("2026-03-08T06:00:00Z"), 2);

            assertThat(instants(fires)).containsExactly(at("2026-03-08T07:00:00Z"), at("2026-03-09T06:00:00Z"));
        }

        @Test
        void everyMinuteRunsStraightThroughBothTransitions() {
            List<Fire> spring = fires(calculator("* * * * *", NEW_YORK), at("2026-03-08T06:58:00Z"), 4);
            List<Fire> autumn = fires(calculator("* * * * *", NEW_YORK), at("2026-11-01T05:58:00Z"), 3);

            assertThat(instants(spring))
                    .containsExactly(
                            at("2026-03-08T06:59:00Z"),
                            at("2026-03-08T07:00:00Z"),
                            at("2026-03-08T07:01:00Z"),
                            at("2026-03-08T07:02:00Z"));
            assertThat(spring.get(1).local()).isEqualTo(LocalDateTime.of(2026, 3, 8, 2, 0));
            assertThat(spring.get(2).local()).isEqualTo(LocalDateTime.of(2026, 3, 8, 3, 1));
            // Under FIRST the repeated hour runs once: 01:59 EDT is followed by 02:00 EST.
            assertThat(instants(autumn))
                    .containsExactly(
                            at("2026-11-01T05:59:00Z"), at("2026-11-01T07:00:00Z"), at("2026-11-01T07:01:00Z"));
        }
    }

    @Nested
    class London {

        @Test
        void gapAndOverlap() {
            assertThat(instants(fires(calculator("30 1 * * *", LONDON), at("2026-03-28T12:00:00Z"), 2)))
                    .containsExactly(at("2026-03-29T01:00:00Z"), at("2026-03-30T00:30:00Z"));
            assertThat(instants(fires(calculator("30 1 * * *", LONDON), at("2026-10-24T12:00:00Z"), 2)))
                    .containsExactly(at("2026-10-25T00:30:00Z"), at("2026-10-26T01:30:00Z"));
        }
    }

    @Nested
    class Sydney {

        @Test
        void gapAndOverlap() {
            // DST starts 2026-10-04 02:00 AEST -> 03:00 AEDT.
            assertThat(instants(fires(calculator("30 2 * * *", SYDNEY), at("2026-10-02T12:00:00Z"), 3)))
                    .containsExactly(
                            at("2026-10-02T16:30:00Z"), at("2026-10-03T16:00:00Z"), at("2026-10-04T15:30:00Z"));
            // DST ends 2026-04-05 03:00 AEDT -> 02:00 AEST.
            assertThat(instants(fires(calculator("30 2 * * *", SYDNEY), at("2026-04-03T12:00:00Z"), 3)))
                    .containsExactly(
                            at("2026-04-03T15:30:00Z"), at("2026-04-04T15:30:00Z"), at("2026-04-05T16:30:00Z"));
        }
    }

    @Nested
    class Kolkata {

        @Test
        void noTransitionsSoTwoAmIsAlwaysTwentyThirtyUtc() {
            List<Instant> fires = instants(fires(calculator("0 2 * * *", KOLKATA), at("2026-03-01T00:00:00Z"), 300));

            for (int i = 0; i < fires.size(); i++) {
                assertThat(fires.get(i)).isEqualTo(at("2026-03-01T20:30:00Z").plus(Duration.ofDays(i)));
            }
        }

        @Test
        void lldExampleNextFireTime() {
            assertThat(calculator("0 2 * * *", KOLKATA).first(at("2026-10-14T09:30:00Z")))
                    .map(Fire::at)
                    .contains(at("2026-10-14T20:30:00Z"));
        }
    }

    @Nested
    class LordHowe {

        @Test
        void thirtyMinuteGap() {
            // 2026-10-04 02:00 +10:30 -> 02:30 +11:00.
            List<Fire> fires = fires(calculator("15 2 * * *", LORD_HOWE), at("2026-10-02T12:00:00Z"), 3);

            assertThat(instants(fires))
                    .containsExactly(
                            at("2026-10-02T15:45:00Z"), at("2026-10-03T15:30:00Z"), at("2026-10-04T15:15:00Z"));
            assertThat(instants(fires(
                            calculator("15 2 * * *", LORD_HOWE, DstPolicy.GAP_SKIP_OVERLAP_BOTH),
                            at("2026-10-02T12:00:00Z"),
                            2)))
                    .containsExactly(at("2026-10-02T15:45:00Z"), at("2026-10-04T15:15:00Z"));
        }

        @Test
        void thirtyMinuteOverlap() {
            // 2026-04-05 02:00 +11:00 -> 01:30 +10:30, so 01:30 to 02:00 occurs twice.
            assertThat(instants(fires(calculator("45 1 * * *", LORD_HOWE), at("2026-04-03T12:00:00Z"), 3)))
                    .containsExactly(
                            at("2026-04-03T14:45:00Z"), at("2026-04-04T14:45:00Z"), at("2026-04-05T15:15:00Z"));
            assertThat(instants(fires(
                            calculator("45 1 * * *", LORD_HOWE, DstPolicy.GAP_SKIP_OVERLAP_BOTH),
                            at("2026-04-04T12:00:00Z"),
                            3)))
                    .containsExactly(
                            at("2026-04-04T14:45:00Z"), at("2026-04-04T15:15:00Z"), at("2026-04-05T15:15:00Z"));
        }
    }

    static Stream<Arguments> zonesAndPolicies() {
        List<Arguments> arguments = new ArrayList<>();
        for (String zone : List.of("America/New_York", "Europe/London", "Australia/Sydney", "Australia/Lord_Howe")) {
            for (DstPolicy policy : DstPolicy.values()) {
                arguments.add(Arguments.of(ZoneId.of(zone), policy));
            }
        }
        return arguments.stream();
    }

    /** Derives both 2026 transitions of each zone and checks the policy at a time inside each. */
    @ParameterizedTest
    @MethodSource("zonesAndPolicies")
    void eachZoneFollowsThePolicyAcrossBothTransitions(ZoneId zone, DstPolicy policy) {
        ZoneOffsetTransition gap = transition(zone, true);
        ZoneOffsetTransition overlap = transition(zone, false);

        LocalDateTime inGap = gap.getDateTimeBefore().plus(gap.getDuration().dividedBy(2));
        List<Instant> gapDay = fireDay(zone, policy, inGap);
        if (policy.gap() == DstPolicy.Gap.SHIFT_FORWARD) {
            assertThat(gapDay).containsExactly(gap.getInstant());
        } else {
            assertThat(gapDay).isEmpty();
        }

        LocalDateTime inOverlap =
                overlap.getDateTimeAfter().plus(overlap.getDuration().abs().dividedBy(2));
        List<Instant> overlapDay = fireDay(zone, policy, inOverlap);
        Instant first = inOverlap.toInstant(overlap.getOffsetBefore());
        Instant second = inOverlap.toInstant(overlap.getOffsetAfter());
        if (policy.overlap() == DstPolicy.Overlap.FIRST) {
            assertThat(overlapDay).containsExactly(first);
        } else {
            assertThat(overlapDay).containsExactly(first, second);
        }
    }

    /** The fires of a daily cron at {@code local}'s time, during the 24h around {@code local}. */
    private static List<Instant> fireDay(ZoneId zone, DstPolicy policy, LocalDateTime local) {
        String expression = local.getMinute() + " " + local.getHour() + " * * *";
        CronCalculator calculator = calculator(expression, zone, policy);
        Instant from = local.minusHours(12).atZone(zone).toInstant();
        Instant to = local.plusHours(12).atZone(zone).toInstant();
        List<Instant> fires = new ArrayList<>();
        Fire fire = calculator.nextFire(from, null).orElseThrow();
        while (fire.at().isBefore(to)) {
            fires.add(fire.at());
            fire = calculator.nextFire(fire.at(), fire.local()).orElseThrow();
        }
        return fires;
    }

    private static ZoneOffsetTransition transition(ZoneId zone, boolean gap) {
        ZoneOffsetTransition transition = zone.getRules().nextTransition(at("2026-01-01T00:00:00Z"));
        while (transition.isGap() != gap) {
            transition = zone.getRules().nextTransition(transition.getInstant());
        }
        return transition;
    }

    @ParameterizedTest
    @ValueSource(strings = {"Asia/Kolkata", "UTC", "America/New_York"})
    void periodIsTheShortestGapBetweenMatches(String zone) {
        assertThat(calculator("0 9 * * 1-5", ZoneId.of(zone)).period()).contains(Duration.ofDays(1));
        assertThat(calculator("*/5 * * * *", ZoneId.of(zone)).period()).contains(Duration.ofMinutes(5));
        assertThat(calculator("0,1 * * * *", ZoneId.of(zone)).period()).contains(Duration.ofMinutes(1));
    }

    @Test
    void fireAtGivesTheLocalTimeOfAStoredInstant() {
        assertThat(calculator("30 1 * * *", NEW_YORK).fireAt(at("2026-11-01T06:30:00Z")))
                .isEqualTo(new Fire(at("2026-11-01T06:30:00Z"), LocalDateTime.of(2026, 11, 1, 1, 30)));
    }

    @Test
    void previewListsTheNextFireTimes() {
        List<Fire> preview = calculator("0 2 * * *", NEW_YORK).preview(at("2026-03-06T12:00:00Z"), 3);

        assertThat(instants(preview))
                .containsExactly(at("2026-03-07T07:00:00Z"), at("2026-03-08T07:00:00Z"), at("2026-03-09T06:00:00Z"));
    }
}
