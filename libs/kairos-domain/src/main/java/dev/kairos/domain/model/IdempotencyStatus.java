package dev.kairos.domain.model;

/** Status of a stored idempotency key ({@code api_idempotency.status}). */
public enum IdempotencyStatus implements CodedEnum {
    /** The first request with this key is still being processed. */
    IN_PROGRESS(1),
    /** The response is stored and is replayed for repeated requests. */
    DONE(2);

    private static final CodeIndex<IdempotencyStatus> INDEX = CodeIndex.of(values());

    private final int code;

    IdempotencyStatus(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }

    /**
     * Returns the status for a database code.
     *
     * @param code the code
     * @return the status
     * @throws IllegalArgumentException if the code is unknown
     */
    public static IdempotencyStatus fromCode(int code) {
        return INDEX.fromCode(code);
    }
}
