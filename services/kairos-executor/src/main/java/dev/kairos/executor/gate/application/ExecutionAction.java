package dev.kairos.executor.gate.application;

import dev.kairos.http.client.CallCancellation;
import dev.kairos.http.client.SendObserver;

/** Performs the call and returns only after its outcome has a durable published or re-queued result. */
@FunctionalInterface
public interface ExecutionAction {

    /**
     * Executes a claimed dispatch.
     *
     * @param context the owned lock and fenced claim
     * @param sendObserver the gate-3 and gate-4 checks to invoke immediately before writing request bytes
     * @throws Exception if no durable result was reached; the lease is left for recovery
     */
    void execute(ExecutionContext context, SendObserver sendObserver) throws Exception;

    /** Executes with a cancellation signal; legacy actions may ignore it. */
    default void execute(ExecutionContext context, SendObserver sendObserver, CallCancellation cancellation)
            throws Exception {
        execute(context, sendObserver);
    }
}
