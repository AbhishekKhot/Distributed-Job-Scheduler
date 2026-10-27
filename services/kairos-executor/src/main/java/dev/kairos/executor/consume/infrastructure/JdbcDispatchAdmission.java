package dev.kairos.executor.consume.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.kairos.executor.consume.application.DispatchAdmission;
import dev.kairos.executor.consume.application.DispatchIds;
import dev.kairos.executor.consume.application.DispatchLimits;
import dev.kairos.executor.consume.application.LocalDispatchLimiter;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.proto.v1.Dispatch;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads dispatch concurrency caps and defers over-limit records in the run's cell transaction. */
public final class JdbcDispatchAdmission implements DispatchAdmission {

    private static final int DEFAULT_TEAM_MAX_INFLIGHT = 2_000;
    private static final int DEFAULT_ENDPOINT_MAX_CONCURRENCY = 200;
    private static final Duration LIMIT_CACHE_TTL = Duration.ofSeconds(30);
    private static final long LIMIT_CACHE_SIZE = 20_000;

    private final JdbcClient control;
    private final CellRouter cells;
    private final LocalDispatchLimiter limiter;
    private final ExecutorConsumerProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Cache<LimitKey, DispatchLimits> limitCache = Caffeine.newBuilder()
            .maximumSize(LIMIT_CACHE_SIZE)
            .expireAfterWrite(LIMIT_CACHE_TTL)
            .build();

    /** Creates the admission adapter. */
    public JdbcDispatchAdmission(
            JdbcClient control,
            CellRouter cells,
            LocalDispatchLimiter limiter,
            ExecutorConsumerProperties properties,
            Clock clock,
            MeterRegistry meters) {
        this.control = Objects.requireNonNull(control, "control");
        this.cells = Objects.requireNonNull(cells, "cells");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    @Override
    public boolean acquireOrDefer(Dispatch dispatch) {
        UUID teamId = DispatchIds.uuid(dispatch.getTeamId());
        UUID endpointId = DispatchIds.uuid(dispatch.getEndpointId());
        UUID runId = DispatchIds.uuid(dispatch.getRunId());
        DispatchLimits limits =
                limitCache.get(new LimitKey(teamId, endpointId, dispatch.getEndpointVersion()), this::loadLimits);
        LocalDispatchLimiter.Result result = limiter.acquire(teamId, endpointId, limits);
        if (result == LocalDispatchLimiter.Result.ACQUIRED) {
            return true;
        }
        boolean changed = defer(dispatch, runId);
        if (changed) {
            meters.counter(
                            "kairos.executor.deferrals",
                            "reason",
                            result == LocalDispatchLimiter.Result.TEAM_LIMIT ? "team_inflight" : "endpoint_concurrency")
                    .increment();
        }
        return false;
    }

    @Override
    public void release(Dispatch dispatch) {
        limiter.release(DispatchIds.uuid(dispatch.getTeamId()), DispatchIds.uuid(dispatch.getEndpointId()));
    }

    private DispatchLimits loadLimits(LimitKey key) {
        return control.sql("""
                        SELECT COALESCE((t.quotas ->> 'maxInflight')::integer, :teamFallback) AS team_limit,
                               COALESCE((v.spec #>> '{limits,maxConcurrency}')::integer, :endpointFallback)
                                   AS endpoint_limit
                        FROM scheduler.teams t
                        JOIN scheduler.api_endpoint_versions v ON v.endpoint_id = :endpointId
                        WHERE t.team_id = :teamId AND v.version = :version
                        """)
                .param("teamFallback", DEFAULT_TEAM_MAX_INFLIGHT)
                .param("endpointFallback", DEFAULT_ENDPOINT_MAX_CONCURRENCY)
                .param("teamId", key.teamId())
                .param("endpointId", key.endpointId())
                .param("version", key.endpointVersion())
                .query((rs, _) -> new DispatchLimits(rs.getInt("team_limit"), rs.getInt("endpoint_limit")))
                .optional()
                .orElse(new DispatchLimits(DEFAULT_TEAM_MAX_INFLIGHT, DEFAULT_ENDPOINT_MAX_CONCURRENCY));
    }

    private record LimitKey(UUID teamId, UUID endpointId, int endpointVersion) {}

    private boolean defer(Dispatch dispatch, UUID runId) {
        UUID targetId = DispatchIds.uuid(dispatch.getTargetId());
        UUID teamId = DispatchIds.uuid(dispatch.getTeamId());
        Instant dueAt = clock.instant().plus(properties.deferDelay());
        int updated = cells.forShard(dispatch.getShard())
                .transactions()
                .execute(jdbc -> jdbc.sql("""
                        UPDATE scheduler.run_inflight
                        SET state = 4, due_at = :dueAt, deferrals = deferrals + 1,
                            owner_node = NULL, lease_until = NULL
                        WHERE shard_id = :shard AND run_id = :runId AND team_id = :teamId
                          AND target_id = :targetId AND fence = :fence AND state = 2 AND NOT cancel_requested
                          AND endpoint_id = :endpointId AND endpoint_version = :endpointVersion
                        """)
                        .param("dueAt", Timestamp.from(dueAt))
                        .param("shard", dispatch.getShard())
                        .param("runId", runId)
                        .param("teamId", teamId)
                        .param("targetId", targetId)
                        .param("endpointId", DispatchIds.uuid(dispatch.getEndpointId()))
                        .param("endpointVersion", dispatch.getEndpointVersion())
                        .param("fence", dispatch.getFence())
                        .update());
        return updated == 1;
    }
}
