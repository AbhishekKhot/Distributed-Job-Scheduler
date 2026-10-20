package dev.kairos.domain.run;

import static dev.kairos.domain.run.RunStatus.CANCELLED;
import static dev.kairos.domain.run.RunStatus.DEAD_LETTERED;
import static dev.kairos.domain.run.RunStatus.DELAYED;
import static dev.kairos.domain.run.RunStatus.QUEUED;
import static dev.kairos.domain.run.RunStatus.RUNNING;
import static dev.kairos.domain.run.RunStatus.SKIPPED;
import static dev.kairos.domain.run.RunStatus.SUCCEEDED;
import static dev.kairos.domain.run.RunStatus.UPSTREAM_FAILED;
import static dev.kairos.domain.run.RunStatus.WAITING;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The run state machine: an explicit table of allowed transitions and the fence rule.
 *
 * <p>Allowed transitions (anything else throws {@link IllegalRunTransitionException}):
 *
 * <table>
 *   <caption>Run transitions</caption>
 *   <tr><th>From</th><th>To</th><th>Cause</th></tr>
 *   <tr><td>WAITING</td><td>QUEUED, DELAYED</td><td>Last upstream task succeeded (due now or later)</td></tr>
 *   <tr><td>WAITING</td><td>UPSTREAM_FAILED, CANCELLED</td><td>An upstream task failed; cancel</td></tr>
 *   <tr><td>QUEUED</td><td>QUEUED</td><td>Re-dispatch by the stuck-queued sweeper</td></tr>
 *   <tr><td>QUEUED</td><td>RUNNING</td><td>Fenced claim by an executor</td></tr>
 *   <tr><td>QUEUED</td><td>DELAYED</td><td>Deferral (quota, rate limit, open breaker)</td></tr>
 *   <tr><td>QUEUED</td><td>CANCELLED, SKIPPED</td><td>Cancel; too late to start</td></tr>
 *   <tr><td>RUNNING</td><td>SUCCEEDED, DEAD_LETTERED</td><td>Settled result</td></tr>
 *   <tr><td>RUNNING</td><td>DELAYED</td><td>Retry scheduled, or deferral reported by the executor</td></tr>
 *   <tr><td>RUNNING</td><td>QUEUED</td><td>Executor lost without a sent marker; re-queued</td></tr>
 *   <tr><td>RUNNING</td><td>CANCELLED, SKIPPED</td><td>Cancel; too late to start</td></tr>
 *   <tr><td>DELAYED</td><td>QUEUED</td><td>Due: dispatched by the delayed poller</td></tr>
 *   <tr><td>DELAYED</td><td>CANCELLED</td><td>Cancel</td></tr>
 *   <tr><td>DEAD_LETTERED</td><td>DELAYED, QUEUED</td><td>Redrive, or a workflow retry</td></tr>
 *   <tr><td>UPSTREAM_FAILED</td><td>WAITING</td><td>Workflow retry</td></tr>
 * </table>
 *
 * <p>{@code SUCCEEDED}, {@code CANCELLED} and {@code SKIPPED} are absorbing. Reopening a finished
 * run starts a new retry series. Fence rule: entering {@code QUEUED} is a dispatch and entering
 * {@code RUNNING} is a claim, and both increment the fence; no other transition changes it.
 *
 * <p>Stateless and thread-safe.
 */
public final class RunStateMachine {

    private static final Map<RunStatus, Set<RunStatus>> ALLOWED = buildTable();

    private RunStateMachine() {}

    private static Map<RunStatus, Set<RunStatus>> buildTable() {
        Map<RunStatus, Set<RunStatus>> table = new EnumMap<>(RunStatus.class);
        for (RunStatus status : RunStatus.values()) {
            table.put(status, Collections.unmodifiableSet(EnumSet.noneOf(RunStatus.class)));
        }
        allow(table, WAITING, QUEUED, DELAYED, UPSTREAM_FAILED, CANCELLED);
        allow(table, QUEUED, QUEUED, RUNNING, DELAYED, CANCELLED, SKIPPED);
        allow(table, RUNNING, SUCCEEDED, DEAD_LETTERED, DELAYED, QUEUED, CANCELLED, SKIPPED);
        allow(table, DELAYED, QUEUED, CANCELLED);
        allow(table, DEAD_LETTERED, DELAYED, QUEUED);
        allow(table, UPSTREAM_FAILED, WAITING);
        return Collections.unmodifiableMap(table);
    }

    private static void allow(
            Map<RunStatus, Set<RunStatus>> table, RunStatus from, RunStatus first, RunStatus... rest) {
        table.put(from, Collections.unmodifiableSet(EnumSet.of(first, rest)));
    }

    /**
     * Tells whether a transition is allowed.
     *
     * @param from the current status
     * @param to the requested status
     * @return true if the table allows it
     */
    public static boolean isAllowed(RunStatus from, RunStatus to) {
        return allowedTargets(from).contains(Objects.requireNonNull(to, "to"));
    }

    /**
     * Returns every status a run may move to from the given one.
     *
     * @param from the current status
     * @return an unmodifiable set, empty for absorbing statuses
     */
    public static Set<RunStatus> allowedTargets(RunStatus from) {
        return Objects.requireNonNull(ALLOWED.get(Objects.requireNonNull(from, "from")));
    }

    /**
     * Tells whether entering a status increments the fence.
     *
     * @param to the status entered
     * @return true for {@code QUEUED} (a dispatch) and {@code RUNNING} (a claim)
     */
    public static boolean incrementsFence(RunStatus to) {
        return to == QUEUED || to == RUNNING;
    }

    /**
     * Moves a run, applying the fence rule.
     *
     * @param from the current status
     * @param to the requested status
     * @param fence the current fence
     * @return the transition with the fence to store
     * @throws IllegalRunTransitionException if the table does not allow the move
     */
    public static RunTransition transition(RunStatus from, RunStatus to, Fence fence) {
        Objects.requireNonNull(fence, "fence");
        if (!isAllowed(from, to)) {
            throw new IllegalRunTransitionException(from, to);
        }
        Fence next = incrementsFence(to) ? fence.next() : fence;
        return new RunTransition(from, to, next, from.isFinished());
    }

    /**
     * Returns the fence of a run created directly in a status.
     *
     * @param initial {@code WAITING} (workflow task with upstream tasks), {@code QUEUED} (due
     *     now) or {@code DELAYED} (due later, for example a spread or paced backfill)
     * @return {@link Fence#INITIAL}, incremented once if the run is created {@code QUEUED} because
     *     creating it dispatches it
     * @throws IllegalArgumentException if a run cannot be created in that status
     */
    public static Fence initialFence(RunStatus initial) {
        Objects.requireNonNull(initial, "initial");
        return switch (initial) {
            case WAITING, DELAYED -> Fence.INITIAL;
            case QUEUED -> Fence.INITIAL.next();
            case RUNNING, SUCCEEDED, DEAD_LETTERED, CANCELLED, SKIPPED, UPSTREAM_FAILED ->
                throw new IllegalArgumentException("A run cannot be created as " + initial);
        };
    }
}
