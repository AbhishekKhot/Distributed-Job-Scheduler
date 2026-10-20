package dev.kairos.persistence.error;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Retries a whole transaction that the database rolled back with a {@link RetryableDatabaseException}.
 *
 * <p>At most {@value #MAX_ATTEMPTS} attempts are made. Before attempt {@code n + 1} the caller waits a random time in
 * {@code [d / 2, d]} with {@code d = baseDelay * 2^(n - 1)}: the jitter keeps transactions that collided once from
 * colliding again in lockstep. Any other exception, and the last retryable one, is thrown to the caller unchanged.
 *
 * <p>The unit of work must be a complete transaction (for example a call to {@code DatabaseTransactions.execute}),
 * so each attempt starts from a clean state. Thread-safe if the random source is.
 */
public final class TransientRetry {

    /** The maximum number of attempts, including the first one. */
    public static final int MAX_ATTEMPTS = 3;

    /** The default delay before the second attempt (before jitter). */
    public static final Duration DEFAULT_BASE_DELAY = Duration.ofMillis(20);

    private final Duration baseDelay;
    private final RandomGenerator random;
    private final Sleeper sleeper;

    /**
     * Waits between attempts. Tests replace it so they never sleep.
     */
    @FunctionalInterface
    public interface Sleeper {

        /**
         * Waits for the given time.
         *
         * @param duration how long to wait
         * @throws InterruptedException if the thread is interrupted while waiting
         */
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * Creates a retry helper.
     *
     * @param baseDelay the delay before the second attempt, before jitter; positive
     * @param random the source of jitter; not security-sensitive
     * @param sleeper how to wait between attempts
     * @throws IllegalArgumentException if {@code baseDelay} is not positive
     */
    public TransientRetry(Duration baseDelay, RandomGenerator random, Sleeper sleeper) {
        Objects.requireNonNull(baseDelay, "baseDelay");
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("The base delay must be positive");
        }
        this.baseDelay = baseDelay;
        this.random = Objects.requireNonNull(random, "random");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * Returns a helper with the default base delay that sleeps the calling thread (a virtual thread in the services).
     *
     * @return the helper
     */
    public static TransientRetry withDefaults() {
        // Looked up per call: the helper is shared between threads, and each thread keeps its own generator.
        RandomGenerator perThread = () -> ThreadLocalRandom.current().nextLong();
        return new TransientRetry(DEFAULT_BASE_DELAY, perThread, duration -> Thread.sleep(duration));
    }

    /**
     * Runs the unit of work, retrying it while it fails with a {@link RetryableDatabaseException}.
     *
     * @param <T> the result type
     * @param work a complete transaction
     * @return the result of the first successful attempt
     * @throws RetryableDatabaseException if the last attempt fails with one, or the thread is interrupted while
     *     waiting (the interrupt flag is restored)
     */
    public <T extends @Nullable Object> T call(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (RetryableDatabaseException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                pause(attempt, e);
            }
        }
    }

    /**
     * Returns the delay before the attempt after {@code failedAttempt}.
     *
     * @param failedAttempt the attempt that just failed, starting at 1
     * @return a delay in {@code [d / 2, d]} with {@code d = baseDelay * 2^(failedAttempt - 1)}
     */
    Duration delayAfter(int failedAttempt) {
        long ceilingNanos = baseDelay.toNanos() << (failedAttempt - 1);
        long floorNanos = ceilingNanos / 2;
        return Duration.ofNanos(random.nextLong(floorNanos, ceilingNanos + 1));
    }

    private void pause(int failedAttempt, RetryableDatabaseException failure) {
        try {
            sleeper.sleep(delayAfter(failedAttempt));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failure.addSuppressed(interrupted);
            throw failure;
        }
    }
}
