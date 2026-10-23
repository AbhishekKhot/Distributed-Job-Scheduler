package dev.kairos.domain.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.kairos.domain.model.DstPolicy;
import dev.kairos.domain.model.MisfirePolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MisfireHandlerTest {

    private static final MisfireHandler HANDLER = MisfireHandler.standard();
    private static final Instant NOW = Instant.parse("2026-10-14T09:30:20Z");
    private static final Instant HORIZON = NOW.plusSeconds(5);

    private static ScheduleCalculator cron(String expression) {
        return ScheduleCalculator.of(ScheduleSpec.Cron.of(expression, "UTC"), DstPolicy.DEFAULT, NOW);
    }

    private static ScheduleCalculator interval(Duration every, Instant anchor) {
        return ScheduleCalculator.of(new ScheduleSpec.Interval(every), DstPolicy.DEFAULT, anchor);
    }

    private static List<Instant> instants(FirePlan plan) {
        return plan.fires().stream().map(Fire::at).toList();
    }

    private static Instant at(String text) {
        return Instant.parse(text);
    }

    @ParameterizedTest
    @EnumSource(MisfirePolicy.class)
    void anOnTimeFireIsCreatedWhateverThePolicy(MisfirePolicy policy) {
        FirePlan plan = HANDLER.plan(cron("30 9 * * *"), at("2026-10-14T09:30:00Z"), null, NOW, HORIZON, policy, 100);

        assertThat(instants(plan)).containsExactly(at("2026-10-14T09:30:00Z"));
        assertThat(plan.misfired()).isFalse();
        assertThat(plan.next()).isEqualTo(new Fire(at("2026-10-15T09:30:00Z"), LocalDateTime.of(2026, 10, 15, 9, 30)));
        assertThat(plan.lastFiredLocal()).isEqualTo(LocalDateTime.of(2026, 10, 14, 9, 30));
    }

    @Test
    void exactlySixtySecondsLateIsStillOnTime() {
        Instant due = NOW.minusSeconds(60);

        FirePlan plan = HANDLER.plan(interval(Duration.ofHours(1), due), due, null, NOW, NOW, MisfirePolicy.SKIP, 100);

        assertThat(instants(plan)).containsExactly(due);
        assertThat(plan.misfired()).isFalse();
    }

    @Test
    void aFireBeyondTheHorizonWaits() {
        LocalDateTime last = LocalDateTime.of(2026, 10, 13, 9, 31);
        FirePlan plan = HANDLER.plan(
                cron("31 9 * * *"), at("2026-10-14T09:31:00Z"), last, NOW, HORIZON, MisfirePolicy.RUN_ALL, 100);

        assertThat(plan.fires()).isEmpty();
        assertThat(plan.next()).isNotNull();
        assertThat(plan.next().at()).isEqualTo(at("2026-10-14T09:31:00Z"));
        assertThat(plan.lastFiredLocal()).isEqualTo(last);
        assertThat(plan.misfired()).isFalse();
    }

    @Test
    void theLookAheadCreatesFiresDueBeforeTheHorizon() {
        FirePlan plan = HANDLER.plan(
                cron("* * * * *"), at("2026-10-14T09:30:00Z"), null, NOW, NOW.plusSeconds(45), MisfirePolicy.SKIP, 100);

        assertThat(instants(plan)).containsExactly(at("2026-10-14T09:30:00Z"), at("2026-10-14T09:31:00Z"));
        assertThat(plan.next().at()).isEqualTo(at("2026-10-14T09:32:00Z"));
    }

    @Test
    void runLatestRunsYesterdaysMissedDailyFireOnce() {
        FirePlan plan = HANDLER.plan(
                cron("0 2 * * *"), at("2026-10-10T02:00:00Z"), null, NOW, HORIZON, MisfirePolicy.RUN_LATEST, 100);

        assertThat(instants(plan)).containsExactly(at("2026-10-14T02:00:00Z"));
        assertThat(plan.misfired()).isTrue();
        assertThat(plan.next().at()).isEqualTo(at("2026-10-15T02:00:00Z"));
        assertThat(plan.lastFiredLocal()).isEqualTo(LocalDateTime.of(2026, 10, 14, 2, 0));
    }

    @Test
    void runLatestDropsMissedFiresWhenAnOnTimeOneExists() {
        FirePlan plan = HANDLER.plan(
                cron("* * * * *"), at("2026-10-11T00:00:00Z"), null, NOW, HORIZON, MisfirePolicy.RUN_LATEST, 100);

        assertThat(instants(plan)).containsExactly(at("2026-10-14T09:30:00Z"));
        assertThat(plan.misfired()).isTrue();
    }

    @Test
    void runAllCatchesUpTheMostRecentMaxCatchupFires() {
        FirePlan plan = HANDLER.plan(
                cron("*/5 * * * *"), at("2026-10-01T00:00:00Z"), null, NOW, HORIZON, MisfirePolicy.RUN_ALL, 100);

        List<Instant> fires = instants(plan);
        assertThat(fires).hasSize(101);
        assertThat(fires.getFirst()).isEqualTo(at("2026-10-14T09:25:00Z").minus(Duration.ofMinutes(5L * 99)));
        assertThat(fires.get(99)).isEqualTo(at("2026-10-14T09:25:00Z"));
        assertThat(fires.getLast()).isEqualTo(at("2026-10-14T09:30:00Z"));
        assertThat(plan.next().at()).isEqualTo(at("2026-10-14T09:35:00Z"));
    }

    @Test
    void runAllCatchesUpEverythingBelowTheCap() {
        FirePlan plan = HANDLER.plan(
                cron("0 * * * *"), at("2026-10-14T06:00:00Z"), null, NOW, HORIZON, MisfirePolicy.RUN_ALL, 100);

        assertThat(instants(plan))
                .containsExactly(
                        at("2026-10-14T06:00:00Z"),
                        at("2026-10-14T07:00:00Z"),
                        at("2026-10-14T08:00:00Z"),
                        at("2026-10-14T09:00:00Z"));
        assertThat(plan.next().at()).isEqualTo(at("2026-10-14T10:00:00Z"));
    }

    @Test
    void runAllWithNoCatchupBehavesLikeSkip() {
        FirePlan plan = HANDLER.plan(
                cron("0 * * * *"), at("2026-10-14T06:00:00Z"), null, NOW, HORIZON, MisfirePolicy.RUN_ALL, 0);

        assertThat(plan.fires()).isEmpty();
        assertThat(plan.misfired()).isTrue();
        assertThat(plan.next().at()).isEqualTo(at("2026-10-14T10:00:00Z"));
    }

    @Test
    void skipDropsMissedFiresAndKeepsOnTimeOnes() {
        LocalDateTime last = LocalDateTime.of(2026, 10, 1, 0, 0);
        FirePlan skipped = HANDLER.plan(
                cron("0 2 * * *"), at("2026-10-10T02:00:00Z"), last, NOW, HORIZON, MisfirePolicy.SKIP, 100);
        FirePlan kept = HANDLER.plan(
                cron("* * * * *"), at("2026-10-10T02:00:00Z"), null, NOW, HORIZON, MisfirePolicy.SKIP, 100);

        assertThat(skipped.fires()).isEmpty();
        assertThat(skipped.misfired()).isTrue();
        assertThat(skipped.next().at()).isEqualTo(at("2026-10-15T02:00:00Z"));
        assertThat(skipped.lastFiredLocal()).isEqualTo(last);
        assertThat(instants(kept)).containsExactly(at("2026-10-14T09:30:00Z"));
    }

    @Test
    void aLateOneTimeScheduleRunsOnceUnlessSkipped() {
        Instant at = at("2026-10-14T08:00:00Z");
        ScheduleCalculator calculator = ScheduleCalculator.of(new ScheduleSpec.OneTime(at), DstPolicy.DEFAULT, NOW);

        FirePlan latest = HANDLER.plan(calculator, at, null, NOW, HORIZON, MisfirePolicy.RUN_LATEST, 100);
        FirePlan skip = HANDLER.plan(calculator, at, null, NOW, HORIZON, MisfirePolicy.SKIP, 100);
        FirePlan onTime = HANDLER.plan(calculator, NOW, null, NOW, HORIZON, MisfirePolicy.SKIP, 100);

        assertThat(instants(latest)).containsExactly(at);
        assertThat(latest.next()).isNull();
        assertThat(skip.fires()).isEmpty();
        assertThat(skip.next()).isNull();
        assertThat(instants(onTime)).containsExactly(NOW);
        assertThat(onTime.next()).isNull();
    }

    @Test
    void aOneSecondIntervalCreatesEveryOnTimeFire() {
        Instant due = NOW.minusSeconds(30);

        FirePlan plan = HANDLER.plan(
                interval(Duration.ofSeconds(1), due), due, null, NOW, HORIZON, MisfirePolicy.RUN_LATEST, 100);

        assertThat(plan.fires()).hasSize(36);
        assertThat(plan.next().at()).isEqualTo(HORIZON.plusSeconds(1));
    }

    @Test
    void onTimeFiresAreCappedPerPlan() {
        Instant due = NOW.minusSeconds(30);

        FirePlan plan = HANDLER.plan(
                interval(Duration.ofSeconds(1), due),
                due,
                null,
                NOW,
                NOW.plus(Duration.ofHours(1)),
                MisfirePolicy.SKIP,
                0);

        assertThat(plan.fires()).hasSize(MisfireHandler.MAX_ON_TIME_FIRES);
        assertThat(plan.next().at()).isEqualTo(due.plusSeconds(MisfireHandler.MAX_ON_TIME_FIRES));
    }

    @Test
    void aLongPauseIsCheap() {
        Instant due = at("2025-10-14T00:00:00Z");

        FirePlan plan = HANDLER.plan(
                interval(Duration.ofSeconds(1), due),
                due,
                null,
                NOW,
                NOW,
                MisfirePolicy.RUN_ALL,
                MisfireHandler.MAX_CATCHUP);

        assertThat(plan.fires()).hasSize(MisfireHandler.MAX_CATCHUP + 61);
        assertThat(plan.fires().getFirst().at()).isEqualTo(NOW.minusSeconds(60 + MisfireHandler.MAX_CATCHUP));
    }

    @Property(tries = 200)
    void runAllCreatesAContiguousTailOfOccurrences(
            @ForAll @IntRange(min = 1, max = 600) int everySec,
            @ForAll @IntRange(min = 0, max = 200_000) int lateSec,
            @ForAll @IntRange(min = 0, max = 50) int maxCatchup) {
        Instant due = NOW.minusSeconds(lateSec);
        ScheduleCalculator calculator = interval(Duration.ofSeconds(everySec), due);

        FirePlan plan = HANDLER.plan(calculator, due, null, NOW, HORIZON, MisfirePolicy.RUN_ALL, maxCatchup);

        List<Instant> fires = instants(plan);
        Instant onTimeFrom = NOW.minus(MisfireHandler.DEFAULT_THRESHOLD);
        long misfired = fires.stream().filter(f -> f.isBefore(onTimeFrom)).count();
        assertThat(misfired).isLessThanOrEqualTo(maxCatchup);
        for (int i = 1; i < fires.size(); i++) {
            assertThat(Duration.between(fires.get(i - 1), fires.get(i))).isEqualTo(Duration.ofSeconds(everySec));
        }
        assertThat(plan.next()).isNotNull();
        assertThat(plan.next().at()).isAfter(HORIZON);
        if (!fires.isEmpty()) {
            assertThat(Duration.between(fires.getLast(), plan.next().at())).isEqualTo(Duration.ofSeconds(everySec));
        }
        long total = Duration.between(due, HORIZON).toSeconds() / everySec + 1;
        long late =
                due.isBefore(onTimeFrom) ? (Duration.between(due, onTimeFrom).toSeconds() - 1) / everySec + 1 : 0;
        assertThat(fires).hasSize((int) (total - late + Math.min(late, maxCatchup)));
    }

    @Test
    void rejectsInvalidArguments() {
        ScheduleCalculator calculator = cron("* * * * *");

        assertThatIllegalArgumentException().isThrownBy(() -> new MisfireHandler(Duration.ZERO));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HANDLER.plan(calculator, NOW, null, NOW, NOW.minusSeconds(1), MisfirePolicy.SKIP, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HANDLER.plan(calculator, NOW, null, NOW, NOW, MisfirePolicy.RUN_ALL, -1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HANDLER.plan(calculator, NOW, null, NOW, NOW, MisfirePolicy.RUN_ALL, 1_001));
    }
}
