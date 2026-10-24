package dev.kairos.executor.gate.application;

/** One ordered step in the dispatch claim or send authorization chain. */
public interface ExecutionGate {

    /** Returns this gate's bounded metric label. */
    String name();

    /** Returns the phase in which this gate must run. */
    Phase phase();

    /** Runs this gate and either continues its phase or drops the dispatch. */
    GateResult apply(ExecutionContext context) throws Exception;

    /** Ordered execution phases. */
    enum Phase {
        /** Before a dispatch can reach the execution action. */
        CLAIM,
        /** Immediately before the HTTP request bytes are written. */
        SEND
    }

    /** Gate decision. */
    enum GateResult {
        /** The next gate in this phase may run. */
        CONTINUE,
        /** The dispatch is stale, cancelled, or already owned and can be safely acknowledged. */
        DROP
    }
}
