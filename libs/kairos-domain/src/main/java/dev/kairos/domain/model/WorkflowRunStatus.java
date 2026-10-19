package dev.kairos.domain.model;

/** Status of a workflow run ({@code workflow_runs.status}). */
public enum WorkflowRunStatus implements CodedEnum {
    /** Tasks are running or waiting for their upstream tasks. */
    RUNNING(1),
    /** A task failed and the run waits for review ({@code BLOCK_FOR_REVIEW}). */
    BLOCKED(2),
    /** Every task succeeded. */
    SUCCEEDED(3),
    /** A task failed and the run ended. */
    FAILED(4),
    /** The run was cancelled. */
    CANCELLED(5);

    private static final CodeIndex<WorkflowRunStatus> INDEX = CodeIndex.of(values());

    private final int code;

    WorkflowRunStatus(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }

    /**
     * Tells whether the workflow run has ended.
     *
     * @return true for {@link #SUCCEEDED}, {@link #FAILED} and {@link #CANCELLED}
     */
    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /**
     * Returns the status for a database code.
     *
     * @param code the code
     * @return the status
     * @throws IllegalArgumentException if the code is unknown
     */
    public static WorkflowRunStatus fromCode(int code) {
        return INDEX.fromCode(code);
    }
}
