package dev.kairos.executor.gate.application;

/** Signals that another executor still holds the run lock and the Kafka record must remain replayable. */
public final class RunLockHeldException extends Exception {

    /** Creates the retryable lock-contention signal. */
    public RunLockHeldException() {
        super("The run lock is already held");
    }
}
