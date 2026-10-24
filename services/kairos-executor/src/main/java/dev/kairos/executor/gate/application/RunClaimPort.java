package dev.kairos.executor.gate.application;

import dev.kairos.domain.run.Fence;
import java.time.Instant;
import java.util.Optional;

/** Cell-database port for claiming and renewing a run's fenced executor lease. */
public interface RunClaimPort {

    /** Claims only the still-queued, uncancelled row described by the dispatch. */
    Optional<Fence> claim(ExecutionContext context, String ownerNode, Instant leaseUntil);

    /** Renews the matching running row; false means its lease or owner no longer matches. */
    boolean renew(ExecutionContext context, String ownerNode, Instant leaseUntil);
}
