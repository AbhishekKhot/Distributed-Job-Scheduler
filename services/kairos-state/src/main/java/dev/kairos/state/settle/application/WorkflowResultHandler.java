package dev.kairos.state.settle.application;

import dev.kairos.domain.model.Outcome;
import dev.kairos.engine.run.InflightRun;
import dev.kairos.proto.v1.Result;
import java.time.Instant;

/**
 * T-262 handoff, called inside the owning cell transaction after task history/state changes.
 * The workflow-run row is already locked. Implementations must join that transaction, persist
 * public/sealed outputs, advance the graph and counters, and use the occurrence's task endpoint pins.
 * Throwing rolls back the entire cell group; Kafka offsets remain uncommitted.
 */
@FunctionalInterface
public interface WorkflowResultHandler {
    void settled(InflightRun run, Result result, Outcome outcome, Instant at);

    /** Holds final task results for replay until T-262 is installed; retries need no DAG advancement. */
    static WorkflowResultHandler pending() {
        return (run, result, outcome, at) -> {
            if (outcome != Outcome.FAILED_RETRYING)
                throw new IllegalStateException("Workflow progression is not installed");
        };
    }
}
