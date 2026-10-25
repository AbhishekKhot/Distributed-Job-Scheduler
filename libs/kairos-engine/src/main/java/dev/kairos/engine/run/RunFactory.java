package dev.kairos.engine.run;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.model.RunState;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.run.Fence;
import dev.kairos.domain.run.RunStateMachine;
import dev.kairos.domain.run.RunStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the {@code run_inflight} rows of new runs for every trigger (schedule, immediate, manual, backfill, redrive)
 * and for workflow tasks that wait for upstream tasks.
 *
 * <p>Each run pins the definition and endpoint versions of its {@link RunTarget}, gets a run id in the target's shard
 * and a due time:
 *
 * <ul>
 *   <li>schedule: the occurrence time plus the schedule's spread. A run due within the dispatch horizon (the fire
 *       poller's 5-second look-ahead, LLD section 4.1) is created {@code QUEUED}, and its dispatch row is published at
 *       the due time; a run due later is created {@code DELAYED} for the delayed poller;
 *   <li>immediate and manual: due now, created {@code QUEUED};
 *   <li>backfill: created {@code DELAYED}, paced at a given rate per second from now (100/s by default), so a large
 *       backfill never floods the dispatch topics; the delayed poller enforces its own caps on top;
 *   <li>redrive: the run is re-created under its original run id with the next attempt number and a new retry series
 *       starting now, {@code DELAYED} and due now, so the delayed poller paces redrives (100/s);
 *   <li>waiting: a workflow task with unfinished upstream tasks, created {@code WAITING}.
 * </ul>
 *
 * <p>The fence follows the run state machine: a run created {@code QUEUED} starts at fence 1 (creating it dispatches
 * it), any other at 0, and a redriven run continues from the fence it had when it was dead-lettered, so copies of its
 * old dispatch messages can never match it again.
 *
 * <p>Thread-safe.
 */
public final class RunFactory {

    /** The fire poller's look-ahead: runs due within it are dispatched at creation. */
    public static final Duration DEFAULT_DISPATCH_HORIZON = Duration.ofSeconds(5);

    /** Default backfill pace per job (LLD section 4.1). */
    public static final int DEFAULT_BACKFILL_RATE_PER_SECOND = 100;

    /** Highest backfill pace per job; the platform-wide backfill limit is 1K/s. */
    public static final int MAX_BACKFILL_RATE_PER_SECOND = 1_000;

    /** Most occurrences per backfill call; callers split larger backfills. */
    public static final int MAX_BACKFILL_OCCURRENCES = 1_000;

    /** Longest schedule spread: {@code spread_ms} is below {@code min(60s, period / 10)}. */
    public static final Duration MAX_SPREAD = Duration.ofSeconds(60);

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final KairosIdFactory ids;
    private final Clock clock;
    private final Duration dispatchHorizon;

    /**
     * Creates a factory with the default dispatch horizon.
     *
     * @param ids creates run ids
     * @param clock the time source for "now"
     */
    public RunFactory(KairosIdFactory ids, Clock clock) {
        this(ids, clock, DEFAULT_DISPATCH_HORIZON);
    }

    /**
     * Creates a factory.
     *
     * @param ids creates run ids
     * @param clock the time source for "now"
     * @param dispatchHorizon how far ahead a scheduled run may be due and still be dispatched at creation; not negative
     * @throws IllegalArgumentException if the horizon is negative
     */
    public RunFactory(KairosIdFactory ids, Clock clock, Duration dispatchHorizon) {
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(dispatchHorizon, "dispatchHorizon");
        if (dispatchHorizon.isNegative()) {
            throw new IllegalArgumentException("The dispatch horizon must not be negative");
        }
        this.dispatchHorizon = dispatchHorizon;
    }

    /**
     * Builds the run of a schedule occurrence.
     *
     * @param target what the run executes
     * @param scheduledAt the occurrence time
     * @param spread the schedule's fixed offset ({@code spread_ms}), 0 to 60 seconds
     * @return the run, {@code QUEUED} if due within the dispatch horizon and {@code DELAYED} otherwise
     * @throws IllegalArgumentException if the spread is out of range
     */
    public PlannedRun scheduled(RunTarget target, Instant scheduledAt, Duration spread) {
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        Objects.requireNonNull(spread, "spread");
        if (spread.isNegative() || spread.compareTo(MAX_SPREAD) > 0) {
            throw new IllegalArgumentException("The spread must be 0 to 60 seconds");
        }
        Instant now = clock.instant();
        Instant dueAt = scheduledAt.plus(spread);
        RunState state = dueAt.isAfter(now.plus(dispatchHorizon)) ? RunState.DELAYED : RunState.QUEUED;
        return firstAttempt(target, scheduledAt, dueAt, state, Trigger.SCHEDULE, 0, now);
    }

    /**
     * Builds the run of an {@code IMMEDIATE} job, or a root task of a workflow run started that way.
     *
     * @param target what the run executes
     * @param scheduledAt the occurrence time: now for a job, the workflow run's time for a task
     * @return the run, {@code QUEUED} and due now
     */
    public PlannedRun immediate(RunTarget target, Instant scheduledAt) {
        return dueNow(target, scheduledAt, Trigger.IMMEDIATE);
    }

    /**
     * Builds the run of a "run now" request, or a root task of a workflow run started that way.
     *
     * @param target what the run executes
     * @param scheduledAt the occurrence time: now for a job, the workflow run's time for a task
     * @return the run, {@code QUEUED} and due now
     */
    public PlannedRun manual(RunTarget target, Instant scheduledAt) {
        return dueNow(target, scheduledAt, Trigger.MANUAL);
    }

    /**
     * Builds the runs of past occurrences, paced from now.
     *
     * @param target what the runs execute
     * @param occurrences the occurrence times, distinct, at most {@value #MAX_BACKFILL_OCCURRENCES}
     * @param ratePerSecond the pace, 1 to {@value #MAX_BACKFILL_RATE_PER_SECOND} runs per second
     * @return one {@code DELAYED} run per occurrence, in input order; the i-th is due {@code i / rate} seconds from now
     * @throws IllegalArgumentException if the list is empty, too long or has duplicates, or the rate is out of range
     */
    public List<PlannedRun> backfill(RunTarget target, List<Instant> occurrences, int ratePerSecond) {
        Objects.requireNonNull(occurrences, "occurrences");
        if (occurrences.isEmpty() || occurrences.size() > MAX_BACKFILL_OCCURRENCES) {
            throw new IllegalArgumentException("A backfill has 1 to " + MAX_BACKFILL_OCCURRENCES + " occurrences");
        }
        if (ratePerSecond < 1 || ratePerSecond > MAX_BACKFILL_RATE_PER_SECOND) {
            throw new IllegalArgumentException("The backfill rate must be 1 to " + MAX_BACKFILL_RATE_PER_SECOND + "/s");
        }
        Set<Instant> seen = new HashSet<>();
        for (Instant occurrence : occurrences) {
            if (!seen.add(Objects.requireNonNull(occurrence, "occurrence"))) {
                throw new IllegalArgumentException("A backfill occurrence is listed twice");
            }
        }
        Instant now = clock.instant();
        long gapNanos = NANOS_PER_SECOND / ratePerSecond;
        List<PlannedRun> runs = new ArrayList<>(occurrences.size());
        for (int i = 0; i < occurrences.size(); i++) {
            Instant dueAt = now.plusNanos(gapNanos * i);
            runs.add(firstAttempt(target, occurrences.get(i), dueAt, RunState.DELAYED, Trigger.BACKFILL, 0, now));
        }
        return List.copyOf(runs);
    }

    /**
     * Rebuilds a dead-lettered run for a redrive, under its original run id and occurrence time, with a new retry
     * series.
     *
     * <p>The caller pins either the original or the current versions in the target ({@code redrive_mode}).
     *
     * @param target what the run executes now
     * @param runId the original run id, in the target's shard
     * @param scheduledAt the original occurrence time
     * @param lastAttempt the last attempt the run made before it was dead-lettered, at least 1
     * @param lastFence the run's fence when it was dead-lettered
     * @return the run, {@code DELAYED} and due now, at attempt {@code lastAttempt + 1}, which also starts the series
     * @throws IllegalArgumentException if the run id is in another shard or the attempt is out of range
     */
    public PlannedRun redrive(RunTarget target, RunId runId, Instant scheduledAt, int lastAttempt, Fence lastFence) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        Objects.requireNonNull(lastFence, "lastFence");
        if (lastAttempt < 1 || lastAttempt >= RunRules.MAX_SMALLINT) {
            throw new IllegalArgumentException("The last attempt must be 1 to " + (RunRules.MAX_SMALLINT - 1));
        }
        // The state machine allows DEAD_LETTERED -> DELAYED and keeps the fence on that move.
        Fence fence = RunStateMachine.transition(RunStatus.DEAD_LETTERED, RunStatus.DELAYED, lastFence)
                .fence();
        Instant now = clock.instant();
        int attempt = lastAttempt + 1;
        return new PlannedRun(
                row(target, runId, scheduledAt, now, RunState.DELAYED, attempt, now, fence, 0, Trigger.REDRIVE),
                target.call());
    }

    /**
     * Builds a workflow task that waits for upstream tasks.
     *
     * @param target the task; its target type must be {@code WORKFLOW_TASK}
     * @param scheduledAt the workflow run's occurrence time
     * @param trigger what created the workflow run
     * @param remainingDeps the number of upstream tasks, at least 1
     * @return the run, {@code WAITING}; its due time is set when the last upstream task finishes
     * @throws IllegalArgumentException if the target is a job or {@code remainingDeps} is out of range
     */
    public PlannedRun waiting(RunTarget target, Instant scheduledAt, Trigger trigger, int remainingDeps) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        Objects.requireNonNull(trigger, "trigger");
        if (remainingDeps < 1) {
            throw new IllegalArgumentException("A waiting task has at least one upstream task");
        }
        return firstAttempt(
                target, scheduledAt, scheduledAt, RunState.WAITING, trigger, remainingDeps, clock.instant());
    }

    /**
     * Starts a new workflow task execution after an operator retries its failed occurrence.
     * A new run id isolates old dispatch fences while attempts and retry-series counters continue.
     * @param target pinned workflow task
     * @param scheduledAt original workflow occurrence
     * @param attempt next task attempt number
     * @param remainingDeps unfinished direct parents
     * @return queued when ready, otherwise waiting for its parents
     */
    public PlannedRun workflowRetry(RunTarget target, Instant scheduledAt, int attempt, int remainingDeps) {
        if (target.targetType() != dev.kairos.domain.model.TargetType.WORKFLOW_TASK
                || attempt < 2
                || attempt > RunRules.MAX_SMALLINT
                || remainingDeps < 0
                || remainingDeps > 199) {
            throw new IllegalArgumentException("Invalid workflow retry");
        }
        Instant now = clock.instant();
        RunState state = remainingDeps == 0 ? RunState.QUEUED : RunState.WAITING;
        return new PlannedRun(
                row(
                        target,
                        ids.newRunId(target.shard()),
                        scheduledAt,
                        now,
                        state,
                        attempt,
                        now,
                        RunStateMachine.initialFence(RunStatus.of(state)),
                        remainingDeps,
                        Trigger.MANUAL),
                target.call());
    }

    private PlannedRun dueNow(RunTarget target, Instant scheduledAt, Trigger trigger) {
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        Instant now = clock.instant();
        return firstAttempt(target, scheduledAt, now, RunState.QUEUED, trigger, 0, now);
    }

    private PlannedRun firstAttempt(
            RunTarget target,
            Instant scheduledAt,
            Instant dueAt,
            RunState state,
            Trigger trigger,
            int remainingDeps,
            Instant now) {
        Objects.requireNonNull(target, "target");
        Fence fence = RunStateMachine.initialFence(RunStatus.of(state));
        RunId runId = ids.newRunId(target.shard());
        return new PlannedRun(
                row(target, runId, scheduledAt, dueAt, state, 1, now, fence, remainingDeps, trigger), target.call());
    }

    private static InflightRun row(
            RunTarget target,
            RunId runId,
            Instant scheduledAt,
            Instant dueAt,
            RunState state,
            int attempt,
            Instant seriesStartedAt,
            Fence fence,
            int remainingDeps,
            Trigger trigger) {
        return new InflightRun(
                runId,
                target.teamId(),
                target.targetType(),
                target.definitionId(),
                target.definitionVersion(),
                target.targetId(),
                target.taskKey(),
                target.endpointId(),
                target.endpointVersion(),
                scheduledAt,
                dueAt,
                state,
                attempt,
                attempt,
                seriesStartedAt,
                0,
                fence,
                remainingDeps,
                target.priority(),
                trigger,
                target.maxLatenessMs());
    }
}
