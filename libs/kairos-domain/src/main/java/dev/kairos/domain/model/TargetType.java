package dev.kairos.domain.model;

/**
 * What a schedule or run belongs to ({@code target_type} columns).
 *
 * <p>Schedules use {@link #JOB} or {@link #WORKFLOW}; runs use {@link #JOB} or
 * {@link #WORKFLOW_TASK}, since a workflow executes as one run per task.
 */
public enum TargetType implements CodedEnum {
    /** A job. */
    JOB(1),
    /** One task of a workflow run. */
    WORKFLOW_TASK(2),
    /** A workflow. */
    WORKFLOW(3);

    private static final CodeIndex<TargetType> INDEX = CodeIndex.of(values());

    private final int code;

    TargetType(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }

    /**
     * Returns the target type for a database code.
     *
     * @param code the code
     * @return the target type
     * @throws IllegalArgumentException if the code is unknown
     */
    public static TargetType fromCode(int code) {
        return INDEX.fromCode(code);
    }
}
