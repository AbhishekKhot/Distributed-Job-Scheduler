package dev.kairos.domain.run;

/**
 * Fencing token of a run ({@code run_inflight.fence}, carried by dispatch, result and cancel
 * messages).
 *
 * <p>Every dispatch and every claim increments it (HLD section 6). A claim succeeds only if the
 * dispatch message's fence equals the row's, so a duplicate or stale message, or an executor that
 * lost its Redis lock in a failover, can never claim or settle a run again.
 *
 * @param value the fence, not negative
 */
public record Fence(int value) {

    /** The fence of a run that has never been dispatched. */
    public static final Fence INITIAL = new Fence(0);

    /**
     * Validates the value.
     *
     * @throws IllegalArgumentException if the value is negative
     */
    public Fence {
        if (value < 0) {
            throw new IllegalArgumentException("A fence cannot be negative");
        }
    }

    /**
     * Returns the next fence.
     *
     * @return this value plus one
     * @throws ArithmeticException if the {@code int} range is exhausted
     */
    public Fence next() {
        return new Fence(Math.addExact(value, 1));
    }

    /**
     * Tells whether a fence presented by a message is the current one.
     *
     * @param presented the fence carried by the message
     * @return true if they are equal
     */
    public boolean matches(Fence presented) {
        return value == presented.value;
    }
}
