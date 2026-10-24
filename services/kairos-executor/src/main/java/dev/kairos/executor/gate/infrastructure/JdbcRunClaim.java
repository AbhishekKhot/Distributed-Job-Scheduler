package dev.kairos.executor.gate.infrastructure;

import dev.kairos.domain.run.Fence;
import dev.kairos.executor.gate.application.ExecutionContext;
import dev.kairos.executor.gate.application.RunClaimPort;
import dev.kairos.persistence.cell.CellRouter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** PostgreSQL fenced claim and lease-renewal adapter routed to the run's cell. */
@Repository
public final class JdbcRunClaim implements RunClaimPort {

    private final CellRouter cells;

    /** Creates the claim adapter. */
    public JdbcRunClaim(CellRouter cells) {
        this.cells = Objects.requireNonNull(cells, "cells");
    }

    @Override
    public Optional<Fence> claim(ExecutionContext context, String ownerNode, Instant leaseUntil) {
        var dispatch = context.dispatch();
        return cells.forShard(dispatch.getShard())
                .transactions()
                .execute(jdbc -> jdbc.sql("""
                        UPDATE scheduler.run_inflight
                        SET state = 3, fence = fence + 1, owner_node = :ownerNode, lease_until = :leaseUntil
                        WHERE shard_id = :shard AND run_id = :runId AND team_id = :teamId
                          AND target_id = :targetId AND endpoint_id = :endpointId
                          AND endpoint_version = :endpointVersion AND attempt = :attempt
                          AND infra_attempt = :infraAttempt AND fence = :fence
                          AND state = 2 AND NOT cancel_requested
                        RETURNING fence
                        """)
                        .param("ownerNode", ownerNode)
                        .param("leaseUntil", Timestamp.from(leaseUntil))
                        .param("shard", dispatch.getShard())
                        .param("runId", context.runId().value())
                        .param("teamId", dev.kairos.executor.consume.application.DispatchIds.uuid(dispatch.getTeamId()))
                        .param(
                                "targetId",
                                dev.kairos.executor.consume.application.DispatchIds.uuid(dispatch.getTargetId()))
                        .param(
                                "endpointId",
                                dev.kairos.executor.consume.application.DispatchIds.uuid(dispatch.getEndpointId()))
                        .param("endpointVersion", dispatch.getEndpointVersion())
                        .param("attempt", dispatch.getAttempt())
                        .param("infraAttempt", dispatch.getInfraAttempt())
                        .param("fence", context.dispatchFence().value())
                        .query((rs, _) -> new Fence(rs.getInt("fence")))
                        .optional());
    }

    @Override
    public boolean renew(ExecutionContext context, String ownerNode, Instant leaseUntil) {
        Fence claimedFence =
                context.claimedFence().orElseThrow(() -> new IllegalStateException("The run is not claimed"));
        int updated = cells.forShard(context.dispatch().getShard())
                .transactions()
                .execute(jdbc -> jdbc.sql("""
                        UPDATE scheduler.run_inflight
                        SET lease_until = :leaseUntil
                        WHERE shard_id = :shard AND run_id = :runId AND owner_node = :ownerNode
                          AND fence = :fence AND state = 3 AND NOT cancel_requested
                        """)
                        .param("leaseUntil", Timestamp.from(leaseUntil))
                        .param("shard", context.dispatch().getShard())
                        .param("runId", context.runId().value())
                        .param("ownerNode", ownerNode)
                        .param("fence", claimedFence.value())
                        .update());
        return updated == 1;
    }
}
