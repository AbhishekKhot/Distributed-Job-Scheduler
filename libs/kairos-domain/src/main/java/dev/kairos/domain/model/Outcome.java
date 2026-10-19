package dev.kairos.domain.model;

/** Result of one finished attempt ({@code run_history.outcome}). */
public enum Outcome implements CodedEnum {
    /** The target answered with a success status. */
    SUCCEEDED(1),
    /** The attempt failed and another attempt is scheduled. */
    FAILED_RETRYING(2),
    /** The attempt failed and the run moved to the dead-letter queue. */
    DEAD_LETTERED(3),
    /** The run was cancelled. */
    CANCELLED(4),
    /** The run was not executed, for example because it would have started too late. */
    SKIPPED(5),
    /** A workflow task did not run because an upstream task failed. */
    UPSTREAM_FAILED(6),
    /** The executor holding the run disappeared before reporting a result. */
    EXECUTOR_LOST(7);

    private static final CodeIndex<Outcome> INDEX = CodeIndex.of(values());

    private final int code;

    Outcome(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }

    /**
     * Returns the outcome for a database code.
     *
     * @param code the code
     * @return the outcome
     * @throws IllegalArgumentException if the code is unknown
     */
    public static Outcome fromCode(int code) {
        return INDEX.fromCode(code);
    }
}
