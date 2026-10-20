package dev.kairos.domain.error;

import java.util.Objects;

/**
 * One invalid field of a request, as reported in the problem body's {@code errors} array.
 *
 * <p>Only the field path and a stable code are reported, never the submitted value.
 *
 * @param field the field path, for example {@code name} or {@code schedule.timezone}
 * @param code the stable reason code, for example {@link #PATTERN}
 */
public record FieldError(String field, String code) {

    /** The field is missing or empty. */
    public static final String REQUIRED = "required";

    /** The value is too short or too long. */
    public static final String LENGTH = "length";

    /** The value does not match the allowed pattern. */
    public static final String PATTERN = "pattern";

    /** The value is not in the expected format, for example a malformed id. */
    public static final String FORMAT = "format";

    /** A number is outside the allowed range. */
    public static final String RANGE = "range";

    /**
     * Validates the components.
     *
     * @throws NullPointerException if a component is null
     */
    public FieldError {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
    }
}
