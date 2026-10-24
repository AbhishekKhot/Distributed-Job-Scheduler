package dev.kairos.executor.consume.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DispatchCapacityTest {

    @Test
    void reservesWholeBatchesWithoutCrossingThePodLimit() throws Exception {
        DispatchCapacity capacity = new DispatchCapacity(4_000);
        int contenders = 32;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < contenders; i++) {
                workers.submit(() -> {
                    boolean reserved = capacity.reserve(250);
                    if (reserved) {
                        admitted.incrementAndGet();
                    }
                    ready.countDown();
                    release.await();
                    if (reserved) {
                        capacity.release(250);
                    }
                    return null;
                });
            }
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(admitted).hasValue(16);
            assertThat(capacity.inFlight()).isEqualTo(4_000);
            assertThat(capacity.reserve(1)).isFalse();
            release.countDown();
        }
        assertThat(capacity.inFlight()).isZero();
    }
}
