package dev.kairos.executor.gate.infrastructure;

import dev.kairos.engine.lock.AcquireResult;
import dev.kairos.engine.lock.RunLockPort;
import dev.kairos.executor.gate.application.ExecutionContext;
import dev.kairos.executor.gate.application.ExecutionGate;
import dev.kairos.executor.gate.application.ExecutionGateUnavailableException;
import dev.kairos.executor.gate.application.RunLockHeldException;
import java.util.Objects;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Gate 1: acquire the per-run Redis lock before touching the cell row. */
@Component
@Order(1)
public final class RunLockGate implements ExecutionGate {

    private final RunLockPort locks;

    /** Creates the lock gate. */
    public RunLockGate(RunLockPort locks) {
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    @Override
    public String name() {
        return "run_lock";
    }

    @Override
    public Phase phase() {
        return Phase.CLAIM;
    }

    @Override
    public GateResult apply(ExecutionContext context) throws RunLockHeldException {
        AcquireResult result = locks.acquire(context.runId(), context.dispatchFence(), context.lockTtl());
        if (result instanceof AcquireResult.Acquired acquired) {
            context.acquired(acquired.lock());
            return GateResult.CONTINUE;
        }
        if (result instanceof AcquireResult.Held) {
            throw new RunLockHeldException();
        }
        throw new ExecutionGateUnavailableException("The Redis run lock is unavailable");
    }
}
