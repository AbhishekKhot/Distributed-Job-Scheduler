package dev.kairos.engine.run;

import java.util.Objects;

/**
 * A run to create: its {@code run_inflight} row and the call its dispatch message describes.
 *
 * <p>The call is not stored with the row. It is needed only to build the dispatch message, written to the outbox in
 * the same transaction when the run is created {@code QUEUED}.
 *
 * @param run the row to insert
 * @param call the call timeout and rendered request
 */
public record PlannedRun(InflightRun run, CallSpec call) {

    /** Validates that both parts are present. */
    public PlannedRun {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(call, "call");
    }
}
