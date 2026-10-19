package dev.kairos.domain.error;

import java.util.Set;

/**
 * An optimistic-concurrency precondition failed: the {@code If-Match} version is stale (412) or
 * missing (428).
 */
public final class PreconditionFailedException extends KairosException {

    /**
     * Creates an exception.
     *
     * @param errorCode {@link ErrorCode#VERSION_MISMATCH} or {@link ErrorCode#IF_MATCH_REQUIRED}
     * @param detail a fixed, client-safe description
     * @throws IllegalArgumentException if the code's status is neither 412 nor 428
     */
    public PreconditionFailedException(ErrorCode errorCode, String detail) {
        super(errorCode, detail, Set.of(412, 428));
    }

    /**
     * Creates the standard {@link ErrorCode#VERSION_MISMATCH} exception.
     *
     * @return the exception
     */
    public static PreconditionFailedException versionMismatch() {
        return new PreconditionFailedException(
                ErrorCode.VERSION_MISMATCH,
                "The resource was changed by another request. Fetch it and retry with the new ETag.");
    }
}
