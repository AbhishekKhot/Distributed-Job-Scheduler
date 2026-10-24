package dev.kairos.watcher.recovery;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.model.ErrorClass;
import dev.kairos.domain.model.Outcome;
import dev.kairos.domain.retry.Classification;
import dev.kairos.domain.retry.FailedAttempt;
import dev.kairos.domain.retry.FailureKind;
import dev.kairos.domain.retry.RetryDecision;
import dev.kairos.domain.retry.RetryPlanner;
import dev.kairos.engine.lock.LockInspection;
import dev.kairos.engine.lock.RunLockPort;
import dev.kairos.engine.lock.SentMarker;
import dev.kairos.engine.lock.SentMarkerPort;
import dev.kairos.engine.outbox.OutboxMessage;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.engine.run.DispatchMessageBuilder;
import dev.kairos.engine.run.InflightRun;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.ProtoTimestamps;
import dev.kairos.proto.v1.Dead;
import dev.kairos.watcher.delayed.DelayedDispatchLoader;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Recovers expired executor leases after checking the run lock and sent marker. */
public final class LeaseSweeper implements AutoCloseable {
    private final ShardedPoller.Registration registration;
    private final RecoveryRunStore store;
    private final RunLockPort locks;
    private final SentMarkerPort markers;
    private final RedisFailoverMonitor failover;
    private final RecoveryPolicyLoader policies;
    private final DelayedDispatchLoader dispatchLoader;
    private final RetryPlanner planner;
    private final KairosIdFactory ids;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final MeterRegistry meters;

    /** Registers a five-second sweep under the shard lease guard. */
    public LeaseSweeper(
            ShardedPoller scheduler,
            RecoveryRunStore store,
            RunLockPort locks,
            SentMarkerPort markers,
            RedisFailoverMonitor failover,
            RecoveryPolicyLoader policies,
            DelayedDispatchLoader dispatchLoader,
            RetryPlanner planner,
            KairosIdFactory ids,
            OutboxWriter outbox,
            Clock clock,
            MeterRegistry meters) {
        this.store = Objects.requireNonNull(store, "store");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.markers = Objects.requireNonNull(markers, "markers");
        this.failover = Objects.requireNonNull(failover, "failover");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.dispatchLoader = Objects.requireNonNull(dispatchLoader, "dispatchLoader");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
        registration = scheduler.register("lease-recovery", Duration.ofSeconds(5), 8, this::poll);
    }

    /** Sweeps one shard in its fenced cell transaction. */
    public void poll(Cell cell, int shard) {
        Instant now = clock.instant();
        for (RecoveryRun claimed : store.expired(cell, shard, now)) {
            InflightRun run = claimed.run();
            LockInspection lock = locks.inspect(run.runId());
            if (!(lock instanceof LockInspection.Free)) continue;
            SentMarkerPort.MarkerCheck marker =
                    markers.check(new SentMarker(run.runId(), run.attempt(), run.infraAttempt()));
            if (marker == SentMarkerPort.MarkerCheck.UNAVAILABLE) continue;
            if (marker == SentMarkerPort.MarkerCheck.ABSENT && !failover.quarantined(now)) {
                if (run.infraAttempt() == InflightRun.MAX_INFRA_ATTEMPTS) {
                    deadLetter(cell, claimed, ErrorClass.EXECUTOR_LOST, ErrorClass.EXECUTOR_LOST, now);
                } else {
                    var loaded = RecoveryDispatch.load(dispatchLoader, cell, run);
                    var dispatch = DispatchMessageBuilder.redispatch(
                            RecoveryDispatch.withInfraAttempt(run, run.infraAttempt() + 1), loaded.call(), now, null);
                    store.history(cell, claimed, Outcome.EXECUTOR_LOST, ErrorClass.EXECUTOR_LOST, false, now);
                    store.queued(cell, claimed, dispatch);
                    outbox.write(OutboxMessage.dispatch(dispatch.message(), now));
                    afterCommit(
                            () -> meters.counter("kairos_lease_requeues_total").increment());
                }
                continue;
            }
            var policy = policies.load(cell, run);
            var failed = new FailedAttempt(
                    run.runId(),
                    run.attempt(),
                    run.seriesStart(),
                    run.seriesStartedAt(),
                    new Classification.Failure(ErrorClass.UNKNOWN_OUTCOME, FailureKind.UNKNOWN_OUTCOME),
                    null);
            RetryDecision decision = planner.plan(failed, policy.retry(), policy.unknown());
            if (decision instanceof RetryDecision.Retry retry) {
                store.history(cell, claimed, Outcome.FAILED_RETRYING, ErrorClass.UNKNOWN_OUTCOME, false, now);
                store.retry(cell, claimed, retry.nextAttempt(), retry.dueAt());
                afterCommit(() ->
                        meters.counter("kairos_lease_unknown_retries_total").increment());
            } else if (decision instanceof RetryDecision.DeadLetter dead) {
                deadLetter(cell, claimed, dead.reason(), ErrorClass.UNKNOWN_OUTCOME, now);
            }
        }
    }

    private void deadLetter(Cell cell, RecoveryRun claimed, ErrorClass reason, ErrorClass historyError, Instant now) {
        InflightRun run = claimed.run();
        UUID dlq = ids.newId(run.shard());
        store.history(cell, claimed, Outcome.DEAD_LETTERED, historyError, true, now);
        store.deadLetter(cell, claimed, dlq, reason, now);
        Dead event = Dead.newBuilder()
                .setDlqId(ProtoIds.toBytes(dlq))
                .setRunId(ProtoIds.toBytes(run.runId().value()))
                .setShard(run.shard().value())
                .setTeamId(ProtoIds.toBytes(run.teamId().value()))
                .setTargetType(dev.kairos.proto.v1.TargetType.forNumber(
                        run.targetType().code()))
                .setDefinitionId(ProtoIds.toBytes(run.definitionId()))
                .setDefinitionVersion(run.definitionVersion())
                .setTargetId(ProtoIds.toBytes(run.targetId()))
                .setTaskKey(run.taskKeyColumn())
                .setEndpointId(ProtoIds.toBytes(run.endpointId().value()))
                .setEndpointVersion(run.endpointVersion())
                .setPriority(run.priority().value())
                .setScheduledAt(ProtoTimestamps.toTimestamp(run.scheduledAt()))
                .setAttempt(run.attempt())
                .setInfraAttempt(run.infraAttempt())
                .setReason(dev.kairos.proto.v1.ErrorClass.forNumber(reason.code()))
                .setDeadAt(ProtoTimestamps.toTimestamp(now))
                .build();
        outbox.write(OutboxMessage.dead(event, now, null));
        afterCommit(() -> meters.counter("kairos_lease_dead_letters_total", "reason", reason.name())
                .increment());
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Recovery requires a cell transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    @Override
    public void close() {
        registration.close();
    }
}
