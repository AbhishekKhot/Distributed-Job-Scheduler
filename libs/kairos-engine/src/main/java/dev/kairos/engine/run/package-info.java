/**
 * Run creation: the single place where {@code run_inflight} rows and their dispatch outbox rows are created.
 *
 * <p>{@link dev.kairos.engine.run.RunFactory} builds rows for each trigger from a
 * {@link dev.kairos.engine.run.RunTarget} (pinned definition and endpoint versions, priority, call),
 * {@link dev.kairos.engine.run.RunInserter} inserts them once per occurrence,
 * {@link dev.kairos.engine.run.DispatchMessageBuilder} builds the Protobuf dispatch (and moves an existing run to
 * {@code QUEUED} with a new fence), {@link dev.kairos.engine.run.FireNotifier} wakes the cell's watchers, and
 * {@link dev.kairos.engine.run.RunCreator} ties them together inside the caller's cell transaction.
 */
package dev.kairos.engine.run;
