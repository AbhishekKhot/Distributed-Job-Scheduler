package dev.kairos.executor.gate.infrastructure;

import dev.kairos.domain.run.Fence;
import dev.kairos.engine.lock.RunLock;
import dev.kairos.executor.gate.application.ExecutionContext;
import dev.kairos.executor.gate.application.ExecutionGate;
import dev.kairos.executor.gate.application.RunClaimPort;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Gate 2: claim the still-queued row with the dispatch fence and start its database lease. */
@Component
@Order(2)
public final class FencedClaimGate implements ExecutionGate {

    private final RunClaimPort claims;
    private final Clock clock;

    /** Creates the fenced-claim gate. */
    public FencedClaimGate(RunClaimPort claims, Clock clock) {
        this.claims = Objects.requireNonNull(claims, "claims");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String name() {
        return "fenced_claim";
    }

    @Override
    public Phase phase() {
        return Phase.CLAIM;
    }

    @Override
    public GateResult apply(ExecutionContext context) {
        RunLock lock = context.lock().orElseThrow(() -> new IllegalStateException("The run lock is required"));
        Instant leaseUntil = clock.instant().plus(context.lockTtl());
        Optional<Fence> claimedFence = claims.claim(context, lock.token().nodeId(), leaseUntil);
        if (claimedFence.isEmpty()) {
            return GateResult.DROP;
        }
        context.claimed(claimedFence.orElseThrow(), leaseUntil);
        return GateResult.CONTINUE;
    }
}
