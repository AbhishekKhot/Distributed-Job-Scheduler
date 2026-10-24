package dev.kairos.executor.gate.infrastructure;

import dev.kairos.engine.lock.RemainingResult;
import dev.kairos.engine.lock.RunLock;
import dev.kairos.engine.lock.RunLockPort;
import dev.kairos.executor.gate.application.ExecutionContext;
import dev.kairos.executor.gate.application.ExecutionGate;
import dev.kairos.http.client.SendVetoedException;
import java.time.Duration;
import java.util.Objects;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Gate 3: require enough owned Redis-lock time immediately before the request can be written. */
@Component
@Order(3)
public final class SendTimeGuardGate implements ExecutionGate {

    private final RunLockPort locks;

    /** Creates the send-time guard. */
    public SendTimeGuardGate(RunLockPort locks) {
        this.locks = Objects.requireNonNull(locks, "locks");
    }

    @Override
    public String name() {
        return "send_guard";
    }

    @Override
    public Phase phase() {
        return Phase.SEND;
    }

    @Override
    public GateResult apply(ExecutionContext context) throws SendVetoedException {
        if (context.leaseLost()) {
            throw new SendVetoedException("The executor lease could not be renewed");
        }
        RunLock lock = context.lock().orElseThrow(() -> new SendVetoedException("The run lock is missing"));
        RemainingResult remaining = locks.remaining(lock);
        Duration required = context.timeout().plusSeconds(2);
        if (remaining instanceof RemainingResult.Owned owned && owned.left().compareTo(required) >= 0) {
            return GateResult.CONTINUE;
        }
        throw new SendVetoedException("The run lock does not cover the HTTP call deadline");
    }
}
