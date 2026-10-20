package dev.kairos.http.client;

/**
 * Hook called on the calling thread immediately before the first byte of a request is written: after the connection
 * is open (or taken from the pool) and the TLS handshake is done, and before the request line leaves.
 *
 * <p>The execution gates (T-242) use it for the send-time guard and to write the sent marker. If the observer throws,
 * nothing is written and the call ends with {@link HttpCallOutcome.NotSent}; any exception counts as a veto, so a
 * failing guard fails closed. The observer is called at most once per call; redirects and automatic retries are
 * disabled.
 */
@FunctionalInterface
public interface SendObserver {

    /** An observer that lets every request through. */
    SendObserver NONE = context -> {};

    /**
     * Called just before the request bytes are written.
     *
     * @param context what is about to be sent
     * @throws SendVetoedException to stop the request; nothing is sent
     */
    void beforeSend(SendContext context) throws SendVetoedException;
}
