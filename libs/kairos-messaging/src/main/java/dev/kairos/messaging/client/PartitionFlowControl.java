package dev.kairos.messaging.client;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;

/**
 * Back-pressure for a consumer whose records are processed on other threads (IMPLEMENTATION_CHECKLIST section 4.7):
 * when the records in flight reach the high water mark, every assigned partition is paused; when they drop to the low
 * water mark, they are resumed. The poll loop keeps polling while paused (so the consumer stays in its group and
 * keeps heartbeating) and never sleeps. LLD section 4.1: the executor uses 4K / 3K per pod.
 *
 * <p>Workers call {@link #started} and {@link #finished} from any thread; the poll thread calls {@link #apply} before
 * every poll. Partitions assigned by a rebalance while paused are paused on the next {@link #apply}.
 *
 * <p>Thread-safe; {@link #apply} must run on the consumer's poll thread, as Kafka requires.
 */
public final class PartitionFlowControl {

    private final int highWater;
    private final int lowWater;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean paused;

    /**
     * Creates the control.
     *
     * @param highWater pause at or above this many records in flight; positive
     * @param lowWater resume at or below this many; 0 to {@code highWater - 1}
     * @throws IllegalArgumentException if the marks are out of range
     */
    public PartitionFlowControl(int highWater, int lowWater) {
        if (highWater < 1 || lowWater < 0 || lowWater >= highWater) {
            throw new IllegalArgumentException("Need 0 <= lowWater < highWater");
        }
        this.highWater = highWater;
        this.lowWater = lowWater;
    }

    /**
     * Counts records handed to workers.
     *
     * @param records how many; not negative
     */
    public void started(int records) {
        requireNotNegative(records);
        inFlight.addAndGet(records);
    }

    /**
     * Counts records whose processing ended, successfully or not.
     *
     * @param records how many; not negative
     * @throws IllegalStateException if more records finished than started
     */
    public void finished(int records) {
        requireNotNegative(records);
        int now = inFlight.addAndGet(-records);
        if (now < 0) {
            inFlight.addAndGet(records);
            throw new IllegalStateException("More records finished than started");
        }
    }

    /**
     * Pauses or resumes the consumer's partitions according to the records in flight.
     *
     * @param consumer the consumer, on its poll thread
     * @return true if the partitions are paused after the call
     */
    public boolean apply(Consumer<?, ?> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        int current = inFlight.get();
        if (!paused && current >= highWater) {
            paused = true;
        } else if (paused && current <= lowWater) {
            paused = false;
            Set<TopicPartition> pausedPartitions = consumer.paused();
            if (!pausedPartitions.isEmpty()) {
                consumer.resume(pausedPartitions);
            }
            return false;
        }
        if (paused) {
            // Also covers partitions assigned since the last call; pausing is idempotent.
            Set<TopicPartition> assignment = consumer.assignment();
            if (!assignment.isEmpty()) {
                consumer.pause(assignment);
            }
        }
        return paused;
    }

    /**
     * Returns the records in flight.
     *
     * @return the count
     */
    public int inFlight() {
        return inFlight.get();
    }

    /**
     * Tells whether the partitions are paused.
     *
     * @return true while paused
     */
    public boolean paused() {
        return paused;
    }

    private static void requireNotNegative(int records) {
        if (records < 0) {
            throw new IllegalArgumentException("A record count must not be negative");
        }
    }
}
