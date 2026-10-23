package dev.kairos.domain.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.domain.id.JobId;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.model.DstPolicy;
import dev.kairos.domain.shard.Shard;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class SpreadTest {

    private static final Instant NOW = Instant.parse("2026-10-14T09:30:00Z");

    @Property
    void staysBelowTheBoundAndIsDeterministic(
            @ForAll long msb, @ForAll long lsb, @ForAll @LongRange(min = 1, max = 31_622_400_000L) long periodMs) {
        UUID id = new UUID(msb, lsb);
        Duration period = Duration.ofMillis(periodMs);
        Duration bound = Duration.ofMillis(Math.min(60_000, periodMs / 10));

        Duration spread = Spread.of(id, period);

        assertThat(spread.isNegative()).isFalse();
        if (bound.toMillis() > 1) {
            assertThat(spread).isLessThan(bound);
        } else {
            assertThat(spread).isZero();
        }
        assertThat(Spread.of(id, period)).isEqualTo(spread);
        assertThat(spread.toNanosPart() % 1_000_000).isZero();
    }

    @Test
    void usesTheWholeRangeForADailySchedule() {
        Set<Long> seconds = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            seconds.add(Spread.of(new UUID(0x0192f3a17c2e7b4dL, i), Duration.ofDays(1))
                    .toSeconds());
        }

        assertThat(seconds).hasSize(60);
    }

    @Test
    void followsTheSchedulePeriod() {
        KairosIdFactory ids = new KairosIdFactory(Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
        JobId job = ids.newJobId(Shard.of(7));

        Duration everyMinute =
                Spread.of(job, ScheduleCalculator.of(ScheduleSpec.Cron.of("* * * * *", "UTC"), DstPolicy.DEFAULT, NOW));
        Duration oneTime = Spread.of(job, ScheduleCalculator.of(new ScheduleSpec.OneTime(NOW), DstPolicy.DEFAULT, NOW));
        Duration tinyInterval = Spread.of(job.value(), Duration.ofMillis(10));

        assertThat(everyMinute).isLessThan(Duration.ofSeconds(6));
        assertThat(oneTime).isZero();
        assertThat(tinyInterval).isZero();
    }
}
