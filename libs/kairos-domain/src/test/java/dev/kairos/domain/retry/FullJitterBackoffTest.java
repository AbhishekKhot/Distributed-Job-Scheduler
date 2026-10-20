package dev.kairos.domain.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FullJitterBackoffTest extends RetryBackoffContractTest {

    private static final Duration BASE = Duration.ofSeconds(30);
    private static final Duration CAP = Duration.ofMinutes(10);

    @Override
    RetryBackoff backoff() {
        return RetryStrategy.FULL_JITTER.backoff();
    }

    @ParameterizedTest
    @CsvSource({"1, 30", "2, 60", "3, 120", "5, 480", "6, 600", "20, 600"})
    void drawsAnywhereBelowTheExponentialCeiling(int retry, long ceilingSec) {
        Duration ceiling = Duration.ofSeconds(ceilingSec);

        assertThat(backoff().lowerBound(retry, BASE, CAP)).isZero();
        assertThat(backoff().upperBound(retry, BASE, CAP)).isEqualTo(ceiling);
        assertThat(backoff().delay(retry, BASE, CAP, Draws.lowest())).isZero();
        assertThat(backoff().delay(retry, BASE, CAP, Draws.always(0.5))).isEqualTo(ceiling.dividedBy(2));
        assertThat(backoff().delay(retry, BASE, CAP, Draws.highest())).isLessThan(ceiling);
    }
}
