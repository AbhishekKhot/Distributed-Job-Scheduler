package dev.kairos.persistence.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TransientRetryTest {

    private static final Duration BASE = Duration.ofMillis(20);

    private final List<Duration> sleeps = new ArrayList<>();
    private final TransientRetry retry = new TransientRetry(BASE, new SplittableRandom(7), sleeps::add);

    @Test
    void returnsAtOnceWhenTheFirstAttemptSucceeds() {
        assertThat(retry.call(() -> "ok")).isEqualTo("ok");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retriesRetryableFailuresUntilAnAttemptSucceeds() {
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.call(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw retryable();
            }
            return "committed";
        });

        assertThat(result).isEqualTo("committed");
        assertThat(attempts).hasValue(3);
        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.get(0)).isBetween(BASE.dividedBy(2), BASE);
        assertThat(sleeps.get(1)).isBetween(BASE, BASE.multipliedBy(2));
    }

    @Test
    void givesUpAfterThreeAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        RetryableDatabaseException last = retryable();

        assertThatThrownBy(() -> retry.call(() -> {
                    if (attempts.incrementAndGet() == TransientRetry.MAX_ATTEMPTS) {
                        throw last;
                    }
                    throw retryable();
                }))
                .isSameAs(last);
        assertThat(attempts).hasValue(TransientRetry.MAX_ATTEMPTS);
    }

    @Test
    void doesNotRetryOtherFailures() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.call(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("not transient");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void delaysStayWithinTheirJitterWindow() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(retry.delayAfter(1)).isBetween(Duration.ofMillis(10), Duration.ofMillis(20));
            assertThat(retry.delayAfter(2)).isBetween(Duration.ofMillis(20), Duration.ofMillis(40));
        }
    }

    @Test
    void interruptStopsRetryingAndKeepsTheFlag() {
        TransientRetry interrupted = new TransientRetry(BASE, new SplittableRandom(1), duration -> {
            throw new InterruptedException("stop");
        });
        RetryableDatabaseException failure = retryable();

        try {
            assertThatThrownBy(() -> interrupted.call(() -> {
                        throw failure;
                    }))
                    .isSameAs(failure);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(failure.getSuppressed()).hasSize(1);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void defaultsUseTheDefaultBaseDelay() {
        AtomicInteger attempts = new AtomicInteger();

        String result = TransientRetry.withDefaults().call(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw retryable();
            }
            return "second";
        });

        assertThat(result).isEqualTo("second");
    }

    @Test
    void rejectsANonPositiveBaseDelay() {
        assertThatThrownBy(() -> new TransientRetry(Duration.ZERO, new SplittableRandom(), sleeps::add))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RetryableDatabaseException retryable() {
        return new RetryableDatabaseException(
                RetryableDatabaseException.Reason.SERIALIZATION_FAILURE, new SQLException("serialize", "40001"));
    }
}
