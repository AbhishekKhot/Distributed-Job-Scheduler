package dev.kairos.domain.template;

import dev.kairos.domain.model.ErrorClass;
import java.util.Objects;

/** A call-output failure suitable for the executor's outcome classifier; never includes response contents. */
public final class OutputExtractionException extends RuntimeException {
    /** Stable classification retained in the serialized exception form. */
    private final ErrorClass errorClass;
    /**
     * Creates an output failure without retaining response data.
     * @param errorClass OUTPUT_MISSING or RESPONSE_TOO_LARGE
     */
    public OutputExtractionException(ErrorClass errorClass) {
        super("The response outputs are missing, ambiguous or exceed the size limit.");
        this.errorClass = Objects.requireNonNull(errorClass, "errorClass");
        if (errorClass != ErrorClass.OUTPUT_MISSING && errorClass != ErrorClass.RESPONSE_TOO_LARGE) {
            throw new IllegalArgumentException("not an output extraction error class");
        }
    }
    /** Returns the stable call-failure classification.
     * @return the stable call-failure classification
     */
    public ErrorClass errorClass() {
        return errorClass;
    }
}
