package dev.kairos.testsupport;

import java.time.Duration;
import java.util.concurrent.Callable;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.awaitility.core.ThrowingRunnable;

/**
 * Short-polling waits for asynchronous outcomes, built on Awaitility.
 *
 * <p>Tests never sleep for a fixed time. They wait for a condition, checked every {@value #POLL_INTERVAL_MS} ms
 * starting immediately, and fail with Awaitility's {@code ConditionTimeoutException} if it does not hold within the
 * timeout ({@value #DEFAULT_TIMEOUT_SECONDS} seconds unless given).
 *
 * <p>Stateless and thread-safe.
 */
public final class Awaits {

    /** The default timeout in seconds. */
    public static final long DEFAULT_TIMEOUT_SECONDS = 10;

    /** The poll interval in milliseconds. */
    public static final long POLL_INTERVAL_MS = 50;

    private Awaits() {}

    /**
     * Returns an Awaitility condition factory with the short poll interval and the default timeout.
     *
     * @return a factory to call {@code until...} on
     */
    public static ConditionFactory await() {
        return await(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS));
    }

    /**
     * Returns an Awaitility condition factory with the short poll interval and the given timeout.
     *
     * @param timeout how long to wait before failing
     * @return a factory to call {@code until...} on
     */
    public static ConditionFactory await(Duration timeout) {
        return Awaitility.await()
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(POLL_INTERVAL_MS))
                .atMost(timeout);
    }

    /**
     * Waits until a condition returns {@code true}.
     *
     * @param condition the condition to poll
     * @throws org.awaitility.core.ConditionTimeoutException if it is still false after the default timeout
     */
    public static void until(Callable<Boolean> condition) {
        await().until(condition);
    }

    /**
     * Waits until an assertion stops throwing.
     *
     * @param assertion the assertion to retry, for example an AssertJ chain
     * @throws org.awaitility.core.ConditionTimeoutException if it still fails after the default timeout
     */
    public static void untilAsserted(ThrowingRunnable assertion) {
        await().untilAsserted(assertion);
    }
}
