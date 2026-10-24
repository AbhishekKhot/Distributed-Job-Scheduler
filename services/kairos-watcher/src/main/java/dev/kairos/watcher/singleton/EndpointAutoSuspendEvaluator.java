package dev.kairos.watcher.singleton;

import dev.kairos.domain.health.EndpointHealthWindow;
import dev.kairos.domain.health.EndpointSuspendRule;
import dev.kairos.domain.health.SuspendDecision;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.tx.ControlTransactions;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Evaluates recent endpoint health and applies the same persisted suspension effects as API 31. */
public final class EndpointAutoSuspendEvaluator {
    private static final String HEALTH = """
            SELECT endpoint_id, sum(attempts)::bigint AS attempts, sum(successes)::bigint AS successes,
                   sum(dead_lettered)::bigint AS dead_lettered
              FROM scheduler.endpoint_health_minute
             WHERE minute > :from AND minute <= :now
             GROUP BY endpoint_id
            """;
    private static final String ELIGIBLE = """
            SELECT endpoint_id, team_id, status_changed_at
              FROM scheduler.api_endpoints
             WHERE status = 'ACTIVE'
               AND coalesce((spec #>> '{suspendPolicy,enabled}')::boolean, true)
            """;
    private static final String SUSPEND = """
            UPDATE scheduler.api_endpoints
               SET status = 'SUSPENDED', status_reason = 'AUTO_SUSPENDED',
                   status_changed_at = :now, updated_at = :now
             WHERE endpoint_id = :endpoint AND status = 'ACTIVE'
            """;
    private static final String AUDIT = """
            INSERT INTO scheduler.audit_log
                (audit_id, at, team_id, actor, action, resource_type, resource_id, details)
            VALUES (:id, :at, :team, 'system:auto-suspend', 'ENDPOINT_AUTO_SUSPENDED', 'endpoint', :resource,
                    jsonb_build_object('statusReason', 'AUTO_SUSPENDED', 'threshold', :threshold))
            """;
    private static final String SUSPENDED =
            "SELECT endpoint_id FROM scheduler.api_endpoints WHERE status = 'SUSPENDED'";
    private static final String ACTIVE = "SELECT endpoint_id FROM scheduler.api_endpoints WHERE status = 'ACTIVE'";
    private static final String HOLD = """
            UPDATE scheduler.schedules SET status = 3
             WHERE endpoint_id IN (:endpoints) AND status = 1
            """;
    private static final String RELEASE = """
            UPDATE scheduler.schedules SET status = 1
             WHERE endpoint_id IN (:endpoints) AND status = 3
            """;

    private final CellRouter cells;
    private final JdbcClient control;
    private final ControlTransactions transactions;
    private final KairosIdFactory ids;
    private final Clock clock;
    private final MeterRegistry meters;
    private final EndpointSuspendRule rule;

    /** Creates the evaluator with T-103's default suspend policy. */
    public EndpointAutoSuspendEvaluator(
            CellRouter cells,
            JdbcClient control,
            ControlTransactions transactions,
            KairosIdFactory ids,
            Clock clock,
            MeterRegistry meters) {
        this(cells, control, transactions, ids, clock, meters, EndpointSuspendRule.DEFAULT);
    }

    EndpointAutoSuspendEvaluator(
            CellRouter cells,
            JdbcClient control,
            ControlTransactions transactions,
            KairosIdFactory ids,
            Clock clock,
            MeterRegistry meters,
            EndpointSuspendRule rule) {
        this.cells = Objects.requireNonNull(cells, "cells");
        this.control = Objects.requireNonNull(control, "control");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.rule = Objects.requireNonNull(rule, "rule");
    }

    /** Evaluates every active eligible endpoint, then reconciles held schedules. */
    public void poll() {
        Instant now = clock.instant();
        Map<UUID, Counts> counts = collect(now.minus(rule.window()), now);
        for (CellEndpoint endpoint : control.sql(ELIGIBLE)
                .query((rs, row) -> new CellEndpoint(
                        rs.getObject("endpoint_id", UUID.class),
                        rs.getObject("team_id", UUID.class),
                        rs.getTimestamp("status_changed_at") == null
                                ? null
                                : rs.getTimestamp("status_changed_at").toInstant()))
                .list()) {
            Counts totals = counts.getOrDefault(endpoint.id(), Counts.ZERO);
            SuspendDecision decision = rule.evaluate(totals.window(), endpoint.statusChangedAt(), now);
            if (decision instanceof SuspendDecision.Suspend suspended && suspend(endpoint, suspended.reason(), now)) {
                meters.counter(
                                "kairos_endpoint_suspensions_total",
                                "endpoint_id",
                                endpoint.id().toString())
                        .increment();
            }
        }
        holdSuspendedSchedules();
        releaseResumedSchedules();
    }

    private Map<UUID, Counts> collect(Instant from, Instant now) {
        Map<UUID, Counts> totals = new HashMap<>();
        for (Cell cell : cells.allCells()) {
            cell.jdbc()
                    .sql(HEALTH)
                    .param("from", Timestamp.from(from))
                    .param("now", Timestamp.from(now))
                    .query((rs, row) -> {
                        UUID endpoint = rs.getObject("endpoint_id", UUID.class);
                        Counts counts = new Counts(
                                rs.getLong("attempts"), rs.getLong("successes"), rs.getLong("dead_lettered"));
                        totals.merge(endpoint, counts, Counts::plus);
                        return endpoint;
                    })
                    .list();
        }
        return totals;
    }

    private boolean suspend(CellEndpoint endpoint, SuspendDecision.Reason reason, Instant now) {
        return transactions.execute(jdbc -> {
            int changed = control.sql(SUSPEND)
                    .param("endpoint", endpoint.id())
                    .param("now", Timestamp.from(now))
                    .update();
            if (changed != 1) return false;
            control.sql(AUDIT)
                    .param("id", ids.newId())
                    .param("at", Timestamp.from(now))
                    .param("team", endpoint.teamId())
                    .param("resource", endpoint.id().toString())
                    .param("threshold", reason.name())
                    .update();
            return true;
        });
    }

    private void holdSuspendedSchedules() {
        Set<UUID> suspended =
                new HashSet<>(control.sql(SUSPENDED).query(UUID.class).list());
        if (suspended.isEmpty()) return;
        List<Cell> allCells = cells.allCells();
        for (Cell cell : allCells) {
            cell.transactions().execute(jdbc -> {
                cell.jdbc().sql(HOLD).param("endpoints", suspended).update();
                return null;
            });
        }
    }

    private void releaseResumedSchedules() {
        Set<UUID> active = new HashSet<>(control.sql(ACTIVE).query(UUID.class).list());
        if (active.isEmpty()) return;
        for (Cell cell : cells.allCells()) {
            cell.transactions().execute(jdbc -> {
                cell.jdbc().sql(RELEASE).param("endpoints", active).update();
                return null;
            });
        }
    }

    private record CellEndpoint(
            UUID id, UUID teamId, @Nullable Instant statusChangedAt) {}

    private record Counts(long attempts, long successes, long deadLetters) {
        private static final Counts ZERO = new Counts(0, 0, 0);

        Counts plus(Counts other) {
            return new Counts(attempts + other.attempts, successes + other.successes, deadLetters + other.deadLetters);
        }

        EndpointHealthWindow window() {
            return new EndpointHealthWindow(bounded(attempts), bounded(successes), bounded(deadLetters));
        }

        private static int bounded(long value) {
            return (int) Math.min(Integer.MAX_VALUE, value);
        }
    }
}
