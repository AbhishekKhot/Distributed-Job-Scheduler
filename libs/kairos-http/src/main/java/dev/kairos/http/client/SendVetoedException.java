package dev.kairos.http.client;

import java.io.Serial;

/**
 * Thrown by a {@link SendObserver} to stop a request before any of its bytes are written; for example when the run
 * lock no longer has enough time left (send-time guard) or the sent marker cannot be written. The call then ends with
 * {@link HttpCallOutcome.NotSent}.
 */
public final class SendVetoedException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message why the send was stopped; must not contain secrets
     */
    public SendVetoedException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message why the send was stopped; must not contain secrets
     * @param cause the underlying failure
     */
    public SendVetoedException(String message, Throwable cause) {
        super(message, cause);
    }
}
