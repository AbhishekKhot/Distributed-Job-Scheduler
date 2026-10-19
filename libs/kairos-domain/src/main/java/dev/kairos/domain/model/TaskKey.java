package dev.kairos.domain.model;

import org.jspecify.annotations.Nullable;

/**
 * Key of a task within a workflow ({@code run_inflight.task_key}), for example
 * {@code createOrder}.
 *
 * <p>1 to 64 characters matching {@code ^[A-Za-z][A-Za-z0-9_-]{0,63}$}. Dots and spaces are not
 * allowed, so a key can be used unambiguously in placeholders such as
 * {@code {{ tasks.createOrder.outputs.orderId }}}. Jobs store an empty task key, which is not a
 * valid {@code TaskKey}.
 *
 * @param value the validated key
 */
public record TaskKey(String value) {

    /** Allowed characters: letters, digits, underscore and hyphen, starting with a letter. */
    public static final String PATTERN = "^[A-Za-z][A-Za-z0-9_-]{0,63}$";

    private static final NameRule RULE = new NameRule("taskKey", 1, 64, PATTERN);

    /**
     * Validates the key.
     *
     * @throws dev.kairos.domain.error.ValidationFailedException on field {@code taskKey} if the
     *     value is missing, longer than 64 characters or does not match {@link #PATTERN}
     */
    public TaskKey {
        RULE.check(value);
    }

    /**
     * Validates and wraps a key.
     *
     * @param value the submitted value, possibly null
     * @return the key
     * @throws dev.kairos.domain.error.ValidationFailedException if the value is invalid
     */
    public static TaskKey of(@Nullable String value) {
        return new TaskKey(RULE.check(value));
    }

    @Override
    public String toString() {
        return value;
    }
}
