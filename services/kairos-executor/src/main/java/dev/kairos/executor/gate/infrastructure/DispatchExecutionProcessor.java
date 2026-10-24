package dev.kairos.executor.gate.infrastructure;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.run.Fence;
import dev.kairos.engine.lock.ReleaseResult;
import dev.kairos.engine.lock.RunLock;
import dev.kairos.engine.lock.RunLockPort;
import dev.kairos.engine.lock.SentMarker;
import dev.kairos.executor.consume.application.DispatchIds;
import dev.kairos.executor.consume.application.DispatchProcessor;
import dev.kairos.executor.gate.application.ExecutionAction;
import dev.kairos.executor.gate.application.ExecutionContext;
import dev.kairos.executor.gate.application.ExecutionGateChain;
import dev.kairos.executor.gate.application.RunClaimPort;
import dev.kairos.http.client.SendObserver;
import dev.kairos.proto.v1.Dispatch;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** Executes the ordered lock and claim gates, then delegates to the result-producing execution action. */
@Component
@ConditionalOnBean(ExecutionAction.class)
public final class DispatchExecutionProcessor implements DispatchProcessor {

    private final ExecutionGateChain gates;
    private final RunLockPort locks;
    private final RunClaimPort claims;
    private final ExecutionAction action;
    private final Clock clock;
    private final MeterRegistry meters;
    private final ScheduledExecutorService scheduler;

    /** Creates the dispatch execution pipeline. */
    public DispatchExecutionProcessor(
            ExecutionGateChain gates,
            RunLockPort locks,
            RunClaimPort claims,
            ExecutionAction action,
            Clock clock,
            MeterRegistry meters,
            ScheduledExecutorService scheduler) {
        this.gates = Objects.requireNonNull(gates, "gates");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.action = Objects.requireNonNull(action, "action");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public void process(Dispatch dispatch) throws Exception {
        try {
            DispatchIds.validateForAdmission(dispatch);
        } catch (IllegalArgumentException invalid) {
            rejectInvalidDispatch();
            return;
        }
        Duration timeout = Duration.ofMillis(dispatch.getTimeoutMs());
        Duration lockTtl = timeout.plusSeconds(10);
        if (timeout.isZero()
                || timeout.isNegative()
                || lockTtl.compareTo(RunLockPort.MAX_TTL) > 0
                || dispatch.getFence() < 0
                || dispatch.getAttempt() < 1
                || dispatch.getAttempt() > SentMarker.MAX_ATTEMPT
                || dispatch.getInfraAttempt() < 0
                || dispatch.getInfraAttempt() > SentMarker.MAX_ATTEMPT) {
            rejectInvalidDispatch();
            return;
        }
        RunId runId = new RunId(DispatchIds.uuid(dispatch.getRunId()));
        ExecutionContext context =
                new ExecutionContext(dispatch, runId, new Fence(dispatch.getFence()), timeout, lockTtl);
        boolean durableOutcome = false;
        try {
            if (!gates.claim(context)) {
                return;
            }
            LeaseRenewer renewer = new LeaseRenewer(context, locks, claims, clock, meters, scheduler);
            try {
                SendObserver sendObserver = gates.sendObserver(context);
                action.execute(context, sendObserver);
            } finally {
                renewer.close();
            }
            durableOutcome = true;
        } finally {
            RunLock lock = context.lock().orElse(null);
            boolean claimFailed = context.claimedFence().isEmpty();
            if (lock != null && (durableOutcome || claimFailed)) {
                ReleaseResult result = locks.release(lock);
                meters.counter(
                                "kairos.execution.lock.release",
                                "outcome",
                                result == ReleaseResult.RELEASED ? "released" : "not_released")
                        .increment();
            }
        }
    }

    private void rejectInvalidDispatch() {
        meters.counter(ExecutionGateChain.METRIC, "gate", "dispatch", "outcome", "rejected")
                .increment();
    }
}
