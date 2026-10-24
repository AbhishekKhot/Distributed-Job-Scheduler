package dev.kairos.watcher.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The optional process-wide relay cap permits steady refill without holding rows. */
class RelayRateLimiterTest {
    @Test
    void boundedBucketRefillsAndUnusedReservationsReturn() {
        RelayRateLimiter limiter = new RelayRateLimiter(10);
        Instant start = Instant.parse("2026-10-24T12:00:00Z");
        assertThat(limiter.reserve(12, start)).isEqualTo(10);
        assertThat(limiter.reserve(1, start)).isZero();
        limiter.refund(2);
        assertThat(limiter.reserve(3, start)).isEqualTo(2);
        assertThat(limiter.reserve(10, start.plusMillis(500))).isEqualTo(5);
        assertThat(limiter.reserve(10, start.plusSeconds(2))).isEqualTo(10);
    }

    @Test
    void zeroDisablesTheCap() {
        assertThat(new RelayRateLimiter(0).reserve(2_000, Instant.EPOCH)).isEqualTo(2_000);
    }
}
