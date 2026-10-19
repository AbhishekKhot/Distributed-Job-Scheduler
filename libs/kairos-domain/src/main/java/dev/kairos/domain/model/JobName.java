package dev.kairos.domain.model;

import org.jspecify.annotations.Nullable;

/**
 * Name of a job, unique within a team among non-deleted jobs ({@code jobs.name}), for example
 * {@code nightly-order-sync}.
 *
 * <p>1 to 128 characters matching {@code ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$}. The name is
 * immutable because it determines the job's shard.
 *
 * @param value the validated name
 */
public record JobName(String value) {

    /** Allowed characters: letters, digits, dot, underscore and hyphen, starting with a letter or digit. */
    public static final String PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$";

    /** Maximum length. */
    public static final int MAX_LENGTH = 128;

    private static final NameRule RULE = new NameRule("name", 1, MAX_LENGTH, PATTERN);

    /**
     * Validates the name.
     *
     * @throws dev.kairos.domain.error.ValidationFailedException on field {@code name} if the value
     *     is missing, longer than 128 characters or does not match {@link #PATTERN}
     */
    public JobName {
        RULE.check(value);
    }

    /**
     * Validates and wraps a name.
     *
     * @param value the submitted value, possibly null
     * @return the name
     * @throws dev.kairos.domain.error.ValidationFailedException if the value is invalid
     */
    public static JobName of(@Nullable String value) {
        return new JobName(RULE.check(value));
    }

    @Override
    public String toString() {
        return value;
    }
}
