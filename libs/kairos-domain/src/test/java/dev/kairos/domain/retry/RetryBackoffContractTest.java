package dev.kairos.domain.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.SplittableRandom;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * The {@link RetryBackoff} contract, run against every strategy by a subclass per implementation.
 */
abstract class RetryBackoffContractTest {

    private static final int GRID = 200;

    /** Returns the implementation under test. */
    abstract RetryBackoff backoff();

    @Property
    void delayStaysWithinItsBoundsAndTheCap(
            @ForAll @IntRange(min = 1, max = 70) int retry,
            @ForAll @IntRange(min = 1, max = 3_600) int baseSec,
            @ForAll @IntRange(min = 0, max = 82_800) int extraSec,
            @ForAll @DoubleRange(min = 0, max = 1, maxIncluded = false) double fraction) {
        Duration base = Duration.ofSeconds(baseSec);
        Duration max = Duration.ofSeconds(baseSec + extraSec);

        Duration delay = backoff().delay(retry, base, max, Draws.always(fraction));

        assertThat(delay.isNegative()).isFalse();
        assertThat(delay)
                .isBetween(backoff().lowerBound(retry, base, max), backoff().upperBound(retry, base, max));
        assertThat(backoff().upperBound(retry, base, max)).isLessThanOrEqualTo(max);
    }

    @Property
    void delayStaysWithinBoundsForAnySeed(@ForAll @IntRange(min = 1, max = 10) int retry, @ForAll long seed) {
        Duration base = Duration.ofSeconds(30);
        Duration max = Duration.ofMinutes(10);

        Duration delay = backoff().delay(retry, base, max, new SplittableRandom(seed));

        assertThat(delay)
                .isBetween(backoff().lowerBound(retry, base, max), backoff().upperBound(retry, base, max));
    }

    @Property
    void boundsNeverDecreaseFromOneRetryToTheNext(
            @ForAll @IntRange(min = 1, max = 70) int retry,
            @ForAll @IntRange(min = 1, max = 3_600) int baseSec,
            @ForAll @IntRange(min = 0, max = 82_800) int extraSec) {
        Duration base = Duration.ofSeconds(baseSec);
        Duration max = Duration.ofSeconds(baseSec + extraSec);

        assertThat(backoff().lowerBound(retry + 1, base, max))
                .isGreaterThanOrEqualTo(backoff().lowerBound(retry, base, max));
        assertThat(backoff().upperBound(retry + 1, base, max))
                .isGreaterThanOrEqualTo(backoff().upperBound(retry, base, max));
    }

    @Property
    void sameDrawNeverWaitsLessOnTheNextRetry(
            @ForAll @IntRange(min = 1, max = 70) int retry,
            @ForAll @IntRange(min = 1, max = 3_600) int baseSec,
            @ForAll @IntRange(min = 0, max = 82_800) int extraSec,
            @ForAll @DoubleRange(min = 0, max = 1, maxIncluded = false) double fraction) {
        Duration base = Duration.ofSeconds(baseSec);
        Duration max = Duration.ofSeconds(baseSec + extraSec);

        assertThat(backoff().delay(retry + 1, base, max, Draws.always(fraction)))
                .isGreaterThanOrEqualTo(backoff().delay(retry, base, max, Draws.always(fraction)));
    }

    @Property
    void expectedDelayNeverDecreases(
            @ForAll @IntRange(min = 1, max = 70) int retry,
            @ForAll @IntRange(min = 1, max = 3_600) int baseSec,
            @ForAll @IntRange(min = 0, max = 82_800) int extraSec) {
        Duration base = Duration.ofSeconds(baseSec);
        Duration max = Duration.ofSeconds(baseSec + extraSec);

        // The mean over an evenly spaced grid of draws is the expectation of a uniform draw.
        assertThat(meanMillis(retry + 1, base, max)).isGreaterThanOrEqualTo(meanMillis(retry, base, max));
    }

    private double meanMillis(int retry, Duration base, Duration max) {
        long sum = 0;
        for (int i = 0; i < GRID; i++) {
            sum += backoff()
                    .delay(retry, base, max, Draws.always((i + 0.5) / GRID))
                    .toMillis();
        }
        return (double) sum / GRID;
    }

    @Property
    void sameSeedGivesTheSameDelay(@ForAll @IntRange(min = 1, max = 10) int retry, @ForAll long seed) {
        Duration base = Duration.ofSeconds(30);
        Duration max = Duration.ofMinutes(10);

        assertThat(backoff().delay(retry, base, max, new SplittableRandom(seed)))
                .isEqualTo(backoff().delay(retry, base, max, new SplittableRandom(seed)));
    }

    @Test
    void rejectsInvalidArguments() {
        Duration base = Duration.ofSeconds(30);
        Duration max = Duration.ofMinutes(10);

        assertThatIllegalArgumentException().isThrownBy(() -> backoff().delay(0, base, max, Draws.lowest()));
        assertThatIllegalArgumentException().isThrownBy(() -> backoff().delay(1, Duration.ZERO, max, Draws.lowest()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> backoff().delay(1, Duration.ofSeconds(-1), max, Draws.lowest()));
        assertThatIllegalArgumentException().isThrownBy(() -> backoff().delay(1, max, base, Draws.lowest()));
        assertThatIllegalArgumentException().isThrownBy(() -> backoff().lowerBound(0, base, max));
        assertThatIllegalArgumentException().isThrownBy(() -> backoff().upperBound(0, base, max));
    }
}
