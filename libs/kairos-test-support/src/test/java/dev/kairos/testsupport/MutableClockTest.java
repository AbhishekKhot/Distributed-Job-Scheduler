package dev.kairos.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MutableClockTest {

    private static final Instant START = Instant.parse("2026-10-20T10:00:00Z");

    @Test
    void readsTheStartInstantInUtcUntilMoved() {
        MutableClock clock = MutableClock.at(START);

        assertThat(clock.instant()).isEqualTo(START);
        assertThat(clock.millis()).isEqualTo(START.toEpochMilli());
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void advancesByTheGivenAmount() {
        MutableClock clock = MutableClock.at(START);

        Instant next = clock.advance(Duration.ofSeconds(30));

        assertThat(next).isEqualTo(START.plusSeconds(30));
        assertThat(clock.instant()).isEqualTo(next);
    }

    @Test
    void rejectsNegativeAdvances() {
        MutableClock clock = MutableClock.at(START);

        assertThatThrownBy(() -> clock.advance(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(clock.instant()).isEqualTo(START);
    }

    @Test
    void setMovesInEitherDirection() {
        MutableClock clock = MutableClock.at(START);

        clock.set(START.minusSeconds(3600));

        assertThat(clock.instant()).isEqualTo(START.minusSeconds(3600));
    }

    @Test
    void zonedCopiesShareTheInstant() {
        MutableClock utc = MutableClock.at(START);
        ZoneId kolkata = ZoneId.of("Asia/Kolkata");
        MutableClock local = utc.withZone(kolkata);

        utc.advance(Duration.ofMinutes(5));

        assertThat(local.getZone()).isEqualTo(kolkata);
        assertThat(local.instant()).isEqualTo(START.plusSeconds(300));
        assertThat(utc.withZone(ZoneOffset.UTC)).isSameAs(utc);
        assertThat(local.toString()).contains("Asia/Kolkata");
    }

    @Test
    void createsAClockInAZone() {
        ZoneId newYork = ZoneId.of("America/New_York");

        MutableClock clock = MutableClock.at(START, newYork);

        assertThat(clock.getZone()).isEqualTo(newYork);
        assertThat(clock.instant()).isEqualTo(START);
    }
}
