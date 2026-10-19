package dev.kairos.domain.model;

/**
 * Why an attempt failed ({@code run_history.error_class}, {@code dead_letters.reason}).
 *
 * <p>The name is what the API shows (for example {@code HTTP_5XX}). Whether a class is retried is
 * decided by the outcome classifier and the endpoint's policies, not here.
 */
public enum ErrorClass implements CodedEnum {
    /** No complete response within the endpoint's timeout. */
    TIMEOUT(1),
    /** The target host name could not be resolved. */
    DNS_FAILED(2),
    /** The connection could not be opened, so the request was never sent. */
    CONNECT_FAILED(3),
    /** The TLS handshake or certificate verification failed. */
    TLS_FAILED(4),
    /** The target answered with a 4xx status (other than 429) not configured as success. */
    HTTP_4XX(5),
    /** The target answered 429 Too Many Requests. */
    HTTP_429(6),
    /** The target answered with a 5xx status not configured as success. */
    HTTP_5XX(7),
    /** The request may have reached the target, but no response was received. */
    UNKNOWN_OUTCOME(8),
    /** The response exceeded the size cap. */
    RESPONSE_TOO_LARGE(9),
    /** A required output could not be extracted from the response. */
    OUTPUT_MISSING(10),
    /** The request could not be built from the definition and upstream outputs. */
    RENDER_FAILED(11),
    /** The resolved target address is refused by the platform target policy. */
    TARGET_NOT_ALLOWED(12),
    /** The retry series is older than the policy's maximum age. */
    MAX_AGE_EXCEEDED(13),
    /** The run was lost with its executor more often than allowed. */
    EXECUTOR_LOST(14);

    private static final CodeIndex<ErrorClass> INDEX = CodeIndex.of(values());

    private final int code;

    ErrorClass(int code) {
        this.code = code;
    }

    @Override
    public int code() {
        return code;
    }

    /**
     * Returns the error class for a database code.
     *
     * @param code the code
     * @return the error class
     * @throws IllegalArgumentException if the code is unknown
     */
    public static ErrorClass fromCode(int code) {
        return INDEX.fromCode(code);
    }
}
