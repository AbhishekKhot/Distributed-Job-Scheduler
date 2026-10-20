package dev.kairos.domain.run;

import dev.kairos.domain.model.Outcome;
import dev.kairos.domain.model.RunState;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where a run is in its life: one of the four unfinished {@link RunState}s, or a final
 * {@link Outcome}. These are the run statuses the API reports.
 *
 * <p>{@link Outcome#FAILED_RETRYING} and {@link Outcome#EXECUTOR_LOST} describe a single attempt,
 * after which the run is still unfinished ({@code DELAYED} or {@code QUEUED}), so they have no
 * status here.
 */
public enum RunStatus {
    /** A workflow task waiting for its upstream tasks. */
    WAITING(RunState.WAITING, null),
    /** Dispatched, or about to be, for an executor. */
    QUEUED(RunState.QUEUED, null),
    /** Claimed by an executor. */
    RUNNING(RunState.RUNNING, null),
    /** Waiting for a retry delay, a deferral, backfill pacing or a redrive. */
    DELAYED(RunState.DELAYED, null),
    /** Finished successfully. */
    SUCCEEDED(null, Outcome.SUCCEEDED),
    /** Failed for good and moved to the dead-letter queue; a redrive can reopen it. */
    DEAD_LETTERED(null, Outcome.DEAD_LETTERED),
    /** Cancelled. */
    CANCELLED(null, Outcome.CANCELLED),
    /** Not executed, for example because it would have started too late. */
    SKIPPED(null, Outcome.SKIPPED),
    /** A workflow task that did not run because an upstream task failed; a workflow retry can reopen it. */
    UPSTREAM_FAILED(null, Outcome.UPSTREAM_FAILED);

    private final @Nullable RunState state;
    private final @Nullable Outcome outcome;

    RunStatus(@Nullable RunState state, @Nullable Outcome outcome) {
        this.state = state;
        this.outcome = outcome;
    }

    /**
     * Returns the status of an unfinished run.
     *
     * @param state the {@code run_inflight.state}
     * @return the status
     */
    public static RunStatus of(RunState state) {
        Objects.requireNonNull(state, "state");
        return switch (state) {
            case WAITING -> WAITING;
            case QUEUED -> QUEUED;
            case RUNNING -> RUNNING;
            case DELAYED -> DELAYED;
        };
    }

    /**
     * Returns the status of a finished run.
     *
     * @param outcome the final outcome
     * @return the status
     * @throws IllegalArgumentException for {@link Outcome#FAILED_RETRYING} and
     *     {@link Outcome#EXECUTOR_LOST}, which never end a run
     */
    public static RunStatus of(Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return switch (outcome) {
            case SUCCEEDED -> SUCCEEDED;
            case DEAD_LETTERED -> DEAD_LETTERED;
            case CANCELLED -> CANCELLED;
            case SKIPPED -> SKIPPED;
            case UPSTREAM_FAILED -> UPSTREAM_FAILED;
            case FAILED_RETRYING, EXECUTOR_LOST ->
                throw new IllegalArgumentException(outcome + " ends an attempt, not a run");
        };
    }

    /**
     * Returns the {@code run_inflight.state} of an unfinished run.
     *
     * @return the state, or empty if the run is finished
     */
    public Optional<RunState> state() {
        return Optional.ofNullable(state);
    }

    /**
     * Returns the final outcome of a finished run.
     *
     * @return the outcome, or empty if the run is unfinished
     */
    public Optional<Outcome> outcome() {
        return Optional.ofNullable(outcome);
    }

    /**
     * Tells whether the run is unfinished (it has a {@code run_inflight} row).
     *
     * @return true for {@code WAITING}, {@code QUEUED}, {@code RUNNING} and {@code DELAYED}
     */
    public boolean isInflight() {
        return state != null;
    }

    /**
     * Tells whether the run is finished.
     *
     * @return true for the final outcomes
     */
    public boolean isFinished() {
        return outcome != null;
    }
}
