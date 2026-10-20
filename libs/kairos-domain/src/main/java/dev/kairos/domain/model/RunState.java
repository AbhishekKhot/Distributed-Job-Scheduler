package dev.kairos.domain.model;

/**
 * State of an unfinished run ({@code run_inflight.state}).
 *
 * <p>Finished attempts leave {@code run_inflight} and are recorded with an {@link Outcome}. The
 * allowed transitions live in the run state machine.
 */
public enum RunState implements CodedEnum {
    /** A workflow task waiting for its upstream tasks. */
    WAITING(1),
    /** Due and published (or about to be published) for an executor. */
    QUEUED(2),
    /** Claimed by an executor that holds its lease. */
    RUNNING(3),
    /** Waiting for a retry delay, a deferral or backfill pacing to pass. */
    DELAYED(4);

    private static final CodeIndex<RunState> INDEX = CodeIndex.of(values());

    private final int code;

    RunState(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }

    /**
     * Returns the state for a database code.
     *
     * @param code the code
     * @return the state
     * @throws IllegalArgumentException if the code is unknown
     */
    public static RunState fromCode(int code) {
        return INDEX.fromCode(code);
    }
}
