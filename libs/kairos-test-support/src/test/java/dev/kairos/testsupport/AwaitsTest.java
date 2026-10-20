package dev.kairos.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

class AwaitsTest {

    @Test
    void untilReturnsOnceTheConditionHolds() {
        AtomicInteger polls = new AtomicInteger();

        Awaits.until(() -> polls.incrementAndGet() >= 3);

        assertThat(polls).hasValueGreaterThanOrEqualTo(3);
    }

    @Test
    void untilAssertedRetriesUntilTheAssertionPasses() {
        AtomicInteger polls = new AtomicInteger();

        Awaits.untilAsserted(() -> assertThat(polls.incrementAndGet()).isGreaterThanOrEqualTo(3));

        assertThat(polls).hasValueGreaterThanOrEqualTo(3);
    }

    @Test
    void failsWhenTheTimeoutExpires() {
        assertThatThrownBy(() -> Awaits.await(Duration.ofMillis(200)).until(() -> false))
                .isInstanceOf(ConditionTimeoutException.class);
    }
}
