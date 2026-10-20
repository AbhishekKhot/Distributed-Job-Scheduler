package dev.kairos.domain.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FixedBackoffTest extends RetryBackoffContractTest {

    private static final Duration BASE = Duration.ofSeconds(30);
    private static final Duration CAP = Duration.ofMinutes(10);

    @Override
    RetryBackoff backoff() {
        return RetryStrategy.FIXED.backoff();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 20})
    void everyRetryWaitsTheBaseDelay(int retry) {
        assertThat(backoff().delay(retry, BASE, CAP, Draws.highest())).isEqualTo(BASE);
        assertThat(backoff().lowerBound(retry, BASE, CAP)).isEqualTo(BASE);
        assertThat(backoff().upperBound(retry, BASE, CAP)).isEqualTo(BASE);
    }
}
