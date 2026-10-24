package dev.kairos.executor.gate.application;

/** A required Redis or database gate could not establish a safe execution decision. */
public final class ExecutionGateUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates a sanitized retryable failure. */
    public ExecutionGateUnavailableException(String message) {
        super(message);
    }

    /** Creates a sanitized retryable failure with its cause. */
    public ExecutionGateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
