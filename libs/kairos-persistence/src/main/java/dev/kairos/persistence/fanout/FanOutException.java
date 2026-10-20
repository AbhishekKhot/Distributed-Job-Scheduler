package dev.kairos.persistence.fanout;

/**
 * A fan-out read could not reach every cell: a cell failed with a checked exception, did not answer before the
 * deadline, or the reading thread was interrupted. The API reports it as {@code 503 unavailable}; a partial page is
 * never returned.
 */
public final class FanOutException extends RuntimeException {

    /**
     * Creates an exception.
     *
     * @param message what failed, naming the cell number only
     * @param cause the underlying failure
     */
    public FanOutException(String message, Throwable cause) {
        super(message, cause);
    }
}
