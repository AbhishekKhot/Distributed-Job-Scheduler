package dev.kairos.domain.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.kairos.domain.model.DstPolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScheduleCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-10-14T09:30:00Z");

    private static ScheduleCalculator of(ScheduleSpec spec) {
        return ScheduleCalculator.of(spec, DstPolicy.DEFAULT, NOW);
    }

    @Test
    void picksTheStrategyForEachKind() {
        assertThat(of(new ScheduleSpec.Immediate())).isInstanceOf(ImmediateCalculator.class);
        assertThat(of(new ScheduleSpec.OneTime(NOW))).isInstanceOf(OneTimeCalculator.class);
        assertThat(of(ScheduleSpec.Cron.of("0 2 * * *", "UTC"))).isInstanceOf(CronCalculator.class);
        assertThat(of(new ScheduleSpec.Interval(Duration.ofMinutes(5)))).isInstanceOf(IntervalCalculator.class);
    }

    @Test
    void immediateNeverFires() {
        ScheduleCalculator calculator = of(new ScheduleSpec.Immediate());

        assertThat(calculator.first(NOW)).isEmpty();
        assertThat(calculator.nextFire(NOW, null)).isEmpty();
        assertThat(calculator.period()).isEmpty();
        assertThat(calculator.preview(NOW, 10)).isEmpty();
        assertThat(calculator.fireAt(NOW)).isEqualTo(new Fire(NOW, LocalDateTime.of(2026, 10, 14, 9, 30)));
    }

    @Test
    void oneTimeFiresOnceEvenIfSlightlyInThePast() {
        Instant at = NOW.minusSeconds(30);
        ScheduleCalculator calculator = of(new ScheduleSpec.OneTime(at));
        Fire fire = new Fire(at, LocalDateTime.of(2026, 10, 14, 9, 29, 30));

        assertThat(calculator.first(NOW)).contains(fire);
        assertThat(calculator.nextFire(at.minusNanos(1), null)).contains(fire);
        assertThat(calculator.nextFire(at, null)).isEmpty();
        assertThat(calculator.nextFire(at.minusSeconds(5), fire.local())).isEmpty();
        assertThat(calculator.period()).isEmpty();
        assertThat(calculator.preview(NOW, 3)).containsExactly(fire);
        assertThat(calculator.fireAt(at)).isEqualTo(fire);
    }

    @Test
    void intervalCountsWholePeriodsFromTheAnchor() {
        ScheduleCalculator calculator = of(new ScheduleSpec.Interval(Duration.ofMinutes(15)));

        assertThat(calculator.first(NOW)).map(Fire::at).contains(NOW.plus(Duration.ofMinutes(15)));
        assertThat(calculator.nextFire(NOW.minusSeconds(1), null)).map(Fire::at).contains(NOW);
        assertThat(calculator.nextFire(NOW.minus(Duration.ofDays(1)), null))
                .map(Fire::at)
                .contains(NOW);
        assertThat(calculator.nextFire(NOW.plus(Duration.ofMinutes(44)), null))
                .map(Fire::at)
                .contains(NOW.plus(Duration.ofMinutes(45)));
        assertThat(calculator.nextFire(NOW.plus(Duration.ofMinutes(45)), null))
                .map(Fire::at)
                .contains(NOW.plus(Duration.ofMinutes(60)));
        assertThat(calculator.period()).contains(Duration.ofMinutes(15));
        assertThat(calculator.fireAt(NOW).local()).isEqualTo(LocalDateTime.of(2026, 10, 14, 9, 30));
    }

    @Test
    void intervalIgnoresDaylightSaving() {
        Instant anchor = Instant.parse("2026-03-08T05:00:00Z");
        ScheduleCalculator calculator =
                ScheduleCalculator.of(new ScheduleSpec.Interval(Duration.ofHours(1)), DstPolicy.DEFAULT, anchor);

        assertThat(calculator.preview(anchor, 4).stream().map(Fire::at).toList())
                .containsExactly(
                        Instant.parse("2026-03-08T06:00:00Z"),
                        Instant.parse("2026-03-08T07:00:00Z"),
                        Instant.parse("2026-03-08T08:00:00Z"),
                        Instant.parse("2026-03-08T09:00:00Z"));
    }

    @Test
    void intervalRejectsANonPositivePeriod() {
        assertThatIllegalArgumentException().isThrownBy(() -> new IntervalCalculator(Duration.ZERO, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> new IntervalCalculator(Duration.ofSeconds(-1), NOW));
    }

    @Test
    void previewReturnsAtMostTenFireTimes() {
        ScheduleCalculator calculator = of(ScheduleSpec.Cron.of("*/5 * * * *", "UTC"));

        List<Fire> preview = calculator.preview(NOW, ScheduleCalculator.MAX_PREVIEW);

        assertThat(preview).hasSize(10);
        assertThat(preview.getFirst().at()).isEqualTo(Instant.parse("2026-10-14T09:35:00Z"));
        assertThat(preview.getLast().at()).isEqualTo(Instant.parse("2026-10-14T10:20:00Z"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 11})
    void previewRejectsOtherCounts(int count) {
        ScheduleCalculator calculator = of(ScheduleSpec.Cron.of("*/5 * * * *", "UTC"));

        assertThatIllegalArgumentException().isThrownBy(() -> calculator.preview(NOW, count));
    }
}
