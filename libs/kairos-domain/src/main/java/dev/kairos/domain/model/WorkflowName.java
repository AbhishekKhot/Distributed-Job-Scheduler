package dev.kairos.domain.model;

import org.jspecify.annotations.Nullable;

/**
 * Name of a workflow, unique within a team among non-deleted workflows ({@code workflows.name}),
 * for example {@code order-fulfilment}.
 *
 * <p>Same rule as {@link JobName}: 1 to 128 characters matching
 * {@code ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$}. The name is immutable because it determines the
 * workflow's shard.
 *
 * @param value the validated name
 */
public record WorkflowName(String value) {

    /** Allowed characters, the same as for job names. */
    public static final String PATTERN = JobName.PATTERN;

    private static final NameRule RULE = new NameRule("name", 1, JobName.MAX_LENGTH, PATTERN);

    /**
     * Validates the name.
     *
     * @throws dev.kairos.domain.error.ValidationFailedException on field {@code name} if the value
     *     is missing, longer than 128 characters or does not match {@link #PATTERN}
     */
    public WorkflowName {
        RULE.check(value);
    }

    /**
     * Validates and wraps a name.
     *
     * @param value the submitted value, possibly null
     * @return the name
     * @throws dev.kairos.domain.error.ValidationFailedException if the value is invalid
     */
    public static WorkflowName of(@Nullable String value) {
        return new WorkflowName(RULE.check(value));
    }

    @Override
    public String toString() {
        return value;
    }
}
