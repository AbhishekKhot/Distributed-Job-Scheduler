package dev.kairos.domain.retry;

/** Bounds of the HTTP status codes the classifier accepts. */
final class HttpStatuses {

    /** Lowest valid status. */
    static final int MIN = 100;

    /** Highest valid status. */
    static final int MAX = 599;

    private HttpStatuses() {}

    /**
     * Rejects a status outside 100 to 599.
     *
     * @return the status
     */
    static int require(int status) {
        if (status < MIN || status > MAX) {
            throw new IllegalArgumentException("An HTTP status lies between 100 and 599");
        }
        return status;
    }
}
