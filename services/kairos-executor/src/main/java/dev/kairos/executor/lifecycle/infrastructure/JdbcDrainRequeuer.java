package dev.kairos.executor.lifecycle.infrastructure;

import dev.kairos.engine.lock.RunLock;
import dev.kairos.engine.lock.RunLockPort;
import dev.kairos.engine.outbox.OutboxMessage;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.executor.cancel.infrastructure.InFlightCalls;
import dev.kairos.executor.consume.application.DispatchIds;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.proto.v1.Dispatch;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/** Requeues a claimed dispatch that did not cross the request-send cut-off when this pod drained. */
@Repository
public final class JdbcDrainRequeuer implements InFlightCalls.DrainRequeuer {
    private final CellRouter cells;
    private final OutboxWriter outbox;
    private final RunLockPort locks;
    private final Clock clock;

    /** Creates the cell-local drain recovery adapter. */
    public JdbcDrainRequeuer(CellRouter cells, OutboxWriter outbox, RunLockPort locks, Clock clock) {
        this.cells = Objects.requireNonNull(cells, "cells");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean requeue(Dispatch dispatch, int claimedFence, String ownerNode, RunLock lock) {
        int newFence = Math.addExact(claimedFence, 1);
        var runId = DispatchIds.uuid(dispatch.getRunId());
        var now = clock.instant();
        boolean requeued = cells.forShard(dispatch.getShard()).transactions().execute(jdbc -> {
            int changed = jdbc.sql("""
                            UPDATE scheduler.run_inflight
                               SET state = 2, fence = :newFence, due_at = :dueAt,
                                   owner_node = NULL, lease_until = NULL
                             WHERE shard_id = :shard AND run_id = :runId AND state = 3
                               AND fence = :claimedFence AND owner_node = :ownerNode
                               AND attempt = :attempt AND infra_attempt = :infraAttempt
                               AND NOT cancel_requested
                            """)
                    .param("newFence", newFence)
                    .param("dueAt", Timestamp.from(now))
                    .param("shard", dispatch.getShard())
                    .param("runId", runId)
                    .param("claimedFence", claimedFence)
                    .param("ownerNode", ownerNode)
                    .param("attempt", dispatch.getAttempt())
                    .param("infraAttempt", dispatch.getInfraAttempt())
                    .update();
            if (changed == 1) {
                Dispatch redelivery = dispatch.toBuilder().setFence(newFence).build();
                outbox.write(OutboxMessage.dispatch(redelivery, now));
            }
            return changed == 1;
        });
        if (requeued) locks.release(lock);
        return requeued;
    }
}
