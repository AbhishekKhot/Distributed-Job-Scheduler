package dev.kairos.executor.consume.infrastructure;

import java.util.concurrent.atomic.AtomicInteger;

/** Pod-wide admission counter. Poll batches reserve capacity before Kafka can return records. */
public final class DispatchCapacity {

    private final int limit;
    private final AtomicInteger reservedOrInFlight = new AtomicInteger();

    /** Creates capacity with a positive hard limit. */
    public DispatchCapacity(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.limit = limit;
    }

    /** Reserves a complete poll batch so records cannot overshoot the pod cap. */
    public boolean reserve(int count) {
        if (count < 1 || count > limit) {
            throw new IllegalArgumentException("Reservation must fit the capacity");
        }
        while (true) {
            int current = reservedOrInFlight.get();
            if (current > limit - count) {
                return false;
            }
            if (reservedOrInFlight.compareAndSet(current, current + count)) {
                return true;
            }
        }
    }

    /** Releases unused reservation slots or completed records. */
    public void release(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Release count cannot be negative");
        }
        int current = reservedOrInFlight.addAndGet(-count);
        if (current < 0) {
            reservedOrInFlight.addAndGet(count);
            throw new IllegalStateException("Released more capacity than was reserved");
        }
    }

    /** Returns the current reservations and in-flight records. */
    public int inFlight() {
        return reservedOrInFlight.get();
    }

    /** Returns the hard limit. */
    public int limit() {
        return limit;
    }
}
