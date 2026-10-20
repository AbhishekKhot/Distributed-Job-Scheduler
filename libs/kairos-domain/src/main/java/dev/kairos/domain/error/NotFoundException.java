package dev.kairos.domain.error;

import java.util.Set;

/**
 * A resource does not exist or belongs to another team (status 404).
 *
 * <p>Another team's resources are reported exactly like missing ones, so their existence never
 * leaks.
 */
public final class NotFoundException extends KairosException {

    /**
     * Creates a {@link ErrorCode#NOT_FOUND} exception.
     *
     * @param detail a fixed, client-safe sentence such as "The job does not exist."
     */
    public NotFoundException(String detail) {
        this(ErrorCode.NOT_FOUND, detail);
    }

    /**
     * Creates an exception with a specific not-found code.
     *
     * @param errorCode a code with status 404, for example {@link ErrorCode#USER_NOT_FOUND}
     * @param detail a fixed, client-safe description
     * @throws IllegalArgumentException if the code's status is not 404
     */
    public NotFoundException(ErrorCode errorCode, String detail) {
        super(errorCode, detail, Set.of(404));
    }
}
