package dev.kairos.domain.error;

import java.util.Objects;
import java.util.Set;

/**
 * Base class of every failure the domain reports to a caller.
 *
 * <p>Each exception carries a stable {@link ErrorCode} and a client-safe detail message. The
 * detail is a fixed sentence written by the code that throws: it never contains submitted values,
 * identifiers of other teams, stack traces or database errors, so the API layer can return it
 * as is. Subclasses restrict which codes they accept, so a conflict can never be reported with a
 * not-found code.
 */
public abstract class KairosException extends RuntimeException {

    /** The stable error code. */
    private final ErrorCode errorCode;

    /**
     * Creates an exception.
     *
     * @param errorCode the stable error code
     * @param detail a fixed, client-safe description of the problem
     * @param allowedStatuses the HTTP statuses the subclass accepts for {@code errorCode}
     * @throws IllegalArgumentException if the code's status is not one of {@code allowedStatuses}
     */
    protected KairosException(ErrorCode errorCode, String detail, Set<Integer> allowedStatuses) {
        super(Objects.requireNonNull(detail, "detail"));
        Objects.requireNonNull(errorCode, "errorCode");
        if (!allowedStatuses.contains(errorCode.status())) {
            throw new IllegalArgumentException(
                    errorCode + " cannot be raised as " + getClass().getSimpleName());
        }
        this.errorCode = errorCode;
    }

    /**
     * Returns the stable error code.
     *
     * @return the error code
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * Returns the client-safe detail message; the same as {@link #getMessage()}.
     *
     * @return the detail
     */
    public String detail() {
        return Objects.requireNonNull(getMessage());
    }
}
