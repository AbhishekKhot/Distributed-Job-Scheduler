package dev.kairos.domain.error;

import java.util.List;
import java.util.Set;

/**
 * Input failed validation (status 400).
 *
 * <p>Carries the invalid fields with stable codes. Neither the detail nor the field errors
 * contain the submitted values.
 */
public final class ValidationFailedException extends KairosException {

    /** Detail used when only field errors describe the problem. */
    public static final String DEFAULT_DETAIL = "One or more fields are invalid.";

    /** The invalid fields, unmodifiable. */
    private final List<FieldError> fieldErrors;

    /**
     * Creates a {@link ErrorCode#VALIDATION} exception for the given fields.
     *
     * @param fieldErrors the invalid fields; copied
     */
    public ValidationFailedException(List<FieldError> fieldErrors) {
        this(ErrorCode.VALIDATION, DEFAULT_DETAIL, fieldErrors);
    }

    /**
     * Creates an exception with a specific code with status 400, for example
     * {@link ErrorCode#CYCLE_DETECTED}.
     *
     * @param errorCode a code with status 400
     * @param detail a fixed, client-safe description
     * @param fieldErrors the invalid fields, possibly empty; copied
     * @throws IllegalArgumentException if the code's status is not 400
     */
    public ValidationFailedException(ErrorCode errorCode, String detail, List<FieldError> fieldErrors) {
        super(errorCode, detail, Set.of(400));
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    /**
     * Creates a {@link ErrorCode#VALIDATION} exception for one field.
     *
     * @param field the field path
     * @param code the reason code, for example {@link FieldError#PATTERN}
     * @return the exception
     */
    public static ValidationFailedException of(String field, String code) {
        return new ValidationFailedException(List.of(new FieldError(field, code)));
    }

    /**
     * Returns the invalid fields.
     *
     * @return an unmodifiable list, possibly empty
     */
    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
