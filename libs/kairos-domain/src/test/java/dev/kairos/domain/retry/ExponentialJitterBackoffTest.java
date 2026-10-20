package dev.kairos.domain.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.random.RandomGenerator;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExponentialJitterBackoffTest extends RetryBackoffContractTest {

    private static final Duration BASE = Duration.ofSeconds(30);
    private static final Duration CAP = Duration.ofMinutes(10);

    @Override
    RetryBackoff backoff() {
        return RetryStrategy.EXPONENTIAL_JITTER.backoff();
    }

    @ParameterizedTest
    @CsvSource({"1, 30, 45", "2, 60, 75", "3, 120, 135", "4, 240, 255", "5, 480, 495", "6, 600, 600", "7, 600, 600"})
    void doublesFromTheBaseWithUpToHalfABaseOfJitterUnderTheCap(int retry, long lowerSec, long upperSec) {
        assertThat(backoff().lowerBound(retry, BASE, CAP)).isEqualTo(Duration.ofSeconds(lowerSec));
        assertThat(backoff().upperBound(retry, BASE, CAP)).isEqualTo(Duration.ofSeconds(upperSec));
        assertThat(backoff().delay(retry, BASE, CAP, Draws.lowest())).isEqualTo(Duration.ofSeconds(lowerSec));
        assertThat(backoff().delay(retry, BASE, CAP, Draws.always(0.5)))
                .isEqualTo(Duration.ofMillis(Math.min(600_000, lowerSec * 1000 + 7_500)));
    }

    @Property
    void defaultsReachTheDeadLetterQueueInAboutFifteenMinutes(@ForAll long seed) {
        RandomGenerator random = new java.util.SplittableRandom(seed);
        Duration total = Duration.ZERO;
        for (int retry = 1; retry <= 5; retry++) {
            total = total.plus(backoff().delay(retry, BASE, CAP, random));
        }

        // 30 + 60 + 120 + 240 + 480 = 930s, plus up to 15s of jitter per retry.
        assertThat(total).isBetween(Duration.ofSeconds(930), Duration.ofSeconds(1_005));
    }

    @Test
    void veryHighRetryNumbersSaturateAtTheCapWithoutOverflow() {
        Duration max = Duration.ofDays(1);

        assertThat(backoff().delay(Integer.MAX_VALUE, Duration.ofHours(1), max, Draws.highest()))
                .isEqualTo(max);
        assertThat(backoff().lowerBound(64, Duration.ofSeconds(1), max)).isEqualTo(max);
    }
}
