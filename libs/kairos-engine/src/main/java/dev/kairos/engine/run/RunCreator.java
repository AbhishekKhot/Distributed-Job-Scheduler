package dev.kairos.engine.run;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.model.RunState;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.shard.Shard;
import dev.kairos.engine.outbox.OutboxMessage;
import dev.kairos.engine.outbox.OutboxWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The single place where runs and their outbox rows are created: the API (immediate runs, run now, backfill, redrive),
 * the fire poller, the delayed poller and the settler all go through it.
 *
 * <p>In the caller's open cell transaction it:
 *
 * <ol>
 *   <li>inserts the runs, skipping occurrences that already exist ({@link RunInserter});
 *   <li>writes one dispatch row per inserted {@code QUEUED} run to the outbox, published from the run's due time
 *       ({@link DispatchMessageBuilder}, {@link OutboxWriter});
 *   <li>notifies the cell's watchers ({@link FireNotifier}) when a run queued by the API (not by the fire poller
 *       itself) is already due, so it is published without waiting for the next poll.
 * </ol>
 *
 * <p>All of this joins the caller's transaction, together with the state change that created the runs (a schedule
 * advance, a job insert, a dead-letter update), so runs and dispatch rows commit or roll back together (HLD section 6).
 * Called outside a {@link dev.kairos.persistence.tx.CellTransactions} callback on the runs' cell, it throws
 * {@link IllegalStateException} before writing anything.
 *
 * <p>Thread-safe.
 */
public final class RunCreator {

    private final RunInserter inserter;
    private final OutboxWriter outbox;
    private final FireNotifier notifier;
    private final Clock clock;

    /**
     * Creates the run creator.
     *
     * @param inserter inserts the runs
     * @param outbox writes the dispatch rows
     * @param notifier wakes the watchers
     * @param clock the time source for "due now"
     */
    public RunCreator(RunInserter inserter, OutboxWriter outbox, FireNotifier notifier, Clock clock) {
        this.inserter = Objects.requireNonNull(inserter, "inserter");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates one run.
     *
     * @param run the run to create
     * @param traceParent the W3C trace parent of the transaction, or null; a malformed value is dropped
     * @return whether it was created or its occurrence already existed
     * @throws IllegalStateException if no transaction is open on the run's cell
     */
    public RunCreation create(PlannedRun run, @Nullable String traceParent) {
        return create(List.of(run), traceParent);
    }

    /**
     * Creates runs, all on the cell of the open transaction.
     *
     * @param runs the runs to create, each {@code WAITING}, {@code QUEUED} or {@code DELAYED}; may be empty
     * @param traceParent the W3C trace parent of the transaction, or null; a malformed value is dropped
     * @return the created runs and the ones whose occurrence already existed
     * @throws IllegalArgumentException if a run is {@code RUNNING}, which no run is created as
     * @throws IllegalStateException if a run's cell has no open transaction on this thread
     */
    public RunCreation create(List<PlannedRun> runs, @Nullable String traceParent) {
        Objects.requireNonNull(runs, "runs");
        if (runs.isEmpty()) {
            return new RunCreation(List.of(), List.of());
        }
        List<InflightRun> rows = new ArrayList<>(runs.size());
        for (PlannedRun planned : runs) {
            InflightRun run = Objects.requireNonNull(planned, "run").run();
            if (run.state() == RunState.RUNNING) {
                throw new IllegalArgumentException("A run is created WAITING, QUEUED or DELAYED, never RUNNING");
            }
            rows.add(run);
        }
        Set<RunId> inserted = inserter.insert(rows);

        Instant now = clock.instant();
        List<InflightRun> created = new ArrayList<>(inserted.size());
        List<InflightRun> duplicates = new ArrayList<>(runs.size() - inserted.size());
        List<OutboxMessage> dispatches = new ArrayList<>();
        Set<Shard> wake = new HashSet<>();
        for (PlannedRun planned : runs) {
            InflightRun run = planned.run();
            if (!inserted.contains(run.runId())) {
                duplicates.add(run);
                continue;
            }
            created.add(run);
            if (run.state() == RunState.QUEUED) {
                dispatches.add(OutboxMessage.dispatch(
                        DispatchMessageBuilder.build(run, planned.call(), traceParent), run.dueAt()));
                if (run.trigger() != Trigger.SCHEDULE && !run.dueAt().isAfter(now)) {
                    wake.add(run.shard());
                }
            }
        }
        outbox.write(dispatches);
        notifier.notifyShards(wake);
        return new RunCreation(created, duplicates);
    }
}
