package dev.kairos.domain.run;

import java.io.Serial;

/**
 * A run was asked to move between two statuses the state machine does not connect.
 *
 * <p>This is a programming or concurrency error inside the scheduler, not a client error, so it
 * is not a {@code KairosException}. The message names only the two statuses.
 */
public final class IllegalRunTransitionException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The status the run was in. */
    private final RunStatus from;

    /** The status that was requested. */
    private final RunStatus to;

    /**
     * Creates the exception.
     *
     * @param from the current status
     * @param to the requested status
     */
    public IllegalRunTransitionException(RunStatus from, RunStatus to) {
        super("A run cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    /**
     * Returns the status the run was in.
     *
     * @return the current status
     */
    public RunStatus from() {
        return from;
    }

    /**
     * Returns the status that was requested.
     *
     * @return the requested status
     */
    public RunStatus to() {
        return to;
    }
}
