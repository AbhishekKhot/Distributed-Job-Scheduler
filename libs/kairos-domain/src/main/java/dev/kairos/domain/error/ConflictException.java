package dev.kairos.domain.error;

import java.util.Set;

/**
 * The request conflicts with the current state of a resource (status 409), for example a taken
 * name, a reused idempotency key or an operation on a finished run.
 */
public final class ConflictException extends KairosException {

    /**
     * Creates an exception.
     *
     * @param errorCode a code with status 409, for example {@link ErrorCode#JOB_NAME_TAKEN}
     * @param detail a fixed, client-safe description
     * @throws IllegalArgumentException if the code's status is not 409
     */
    public ConflictException(ErrorCode errorCode, String detail) {
        super(errorCode, detail, Set.of(409));
    }
}
