package dev.kairos.domain.retry;

/**
 * What the executor observed when it made (or tried to make) one call; the input of
 * {@link OutcomeClassifier}.
 *
 * <p>{@code requestSent} is true once any byte of the request may have left the executor (the
 * sent marker is written just before that): from then on the target may have acted on it.
 */
public sealed interface CallResult {

    /**
     * The target answered with a status and the response was read completely.
     *
     * @param status the HTTP status, 100 to 599
     */
    record Responded(int status) implements CallResult {

        /**
         * Validates the status.
         *
         * @throws IllegalArgumentException if the status is outside 100 to 599
         */
        public Responded {
            HttpStatuses.require(status);
        }
    }

    /**
     * The target answered with a status, but the response exceeded the size cap and was not read.
     *
     * @param status the HTTP status, 100 to 599
     */
    record ResponseTooLarge(int status) implements CallResult {

        /**
         * Validates the status.
         *
         * @throws IllegalArgumentException if the status is outside 100 to 599
         */
        public ResponseTooLarge {
            HttpStatuses.require(status);
        }
    }

    /**
     * The target answered, but a required output could not be extracted from the response.
     *
     * @param status the HTTP status, 100 to 599
     */
    record OutputMissing(int status) implements CallResult {

        /**
         * Validates the status.
         *
         * @throws IllegalArgumentException if the status is outside 100 to 599
         */
        public OutputMissing {
            HttpStatuses.require(status);
        }
    }

    /**
     * No complete response arrived within the endpoint's timeout (or the connect timeout).
     *
     * @param requestSent whether the request may have reached the target
     */
    record TimedOut(boolean requestSent) implements CallResult {}

    /**
     * The connection failed or was reset.
     *
     * @param requestSent whether the request may have reached the target
     */
    record ConnectionFailed(boolean requestSent) implements CallResult {}

    /** The target host name could not be resolved; nothing was sent. */
    record DnsFailed() implements CallResult {}

    /** The TLS handshake or certificate verification failed; nothing was sent. */
    record TlsFailed() implements CallResult {}

    /** The resolved address is refused by the platform target policy; nothing was sent. */
    record TargetNotAllowed() implements CallResult {}

    /** The request could not be built from the definition and upstream outputs; nothing was sent. */
    record RenderFailed() implements CallResult {}
}
