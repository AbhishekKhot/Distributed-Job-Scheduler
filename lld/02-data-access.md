# Kairos LLD Part 02: Data Access (hot-path SQL and transactions)

Back to [LLD index](../LLD.md).

## Conventions

- **Cell routing (HLD §7).** Every statement below runs against **one cell database**, chosen by `CellRouter.cellOf(shard_id)`.
  - Batches that span shards (executor claims, state settles) are **grouped by cell**, with one statement or transaction per cell, run in parallel.
  - The watcher's shard leases, fire polls, relay loops and sweepers all work on the cell that owns those shards.
  - Control-database statements (tenants, keys, endpoints, audit, idempotency) are marked **[control]**.

- **Every statement is parameterised.** Named parameters go through Spring `JdbcClient`; arrays use `Connection.createArrayOf`. Statements live as `static final String` constants in the repository classes and are covered by integration tests (part 08). There is no string concatenation of values.
- **Batches use parallel arrays with `unnest(...)`**, so N rows cost one statement and one round trip.
- `:me` is the caller's `node_id`. `:now` is never passed in; SQL uses `now()` (the database clock, HLD §11.7).
- **Each numbered block below is one transaction.**
- **Transactional outbox.** No transaction publishes to Kafka. Each one writes `outbox` rows (§0) next to its state change, and only the `OutboxRelay` (§4c) publishes them.

---

## 0. Writing outbox rows (used by every transaction that causes a message)

```sql
INSERT INTO outbox (shard_id, outbox_id, topic, msg_key, payload, trace_parent, run_id, available_at)
SELECT v.shard_id, v.outbox_id, v.topic, v.msg_key, v.payload, v.trace_parent, v.run_id, ts(v.available_us)
FROM unnest(:shard_ids::smallint[], :outbox_ids::uuid[], :topics::smallint[], :keys::bytea[], :payloads::bytea[],
            :traces::text[], :run_ids::uuid[], :available_us::bigint[])
     AS v(shard_id, outbox_id, topic, msg_key, payload, trace_parent, run_id, available_us);
```

- Dispatch rows use `available_at = due_at`. Cancels and events use `available_at = now()`.
- `outbox_id` is a UUIDv7, so rows that are due at the same moment publish roughly in creation order.

## 1. Watcher: shard leases (every 5s)

```sql
-- 1a renew the shards I hold (self-fencing: shards not returned are dropped locally at once)
UPDATE shard_leases SET lease_until = now() + interval '15 seconds'
WHERE owner_node = :me AND shard_id = ANY(:held::smallint[]) AND lease_until > now()
RETURNING shard_id, generation;

-- 1b acquire free or expired shards, up to my fair share (want = ceil(256 / liveWatchers) - held)
UPDATE shard_leases sl
SET owner_node = :me, generation = sl.generation + 1, lease_until = now() + interval '15 seconds'
FROM (SELECT shard_id FROM shard_leases
      WHERE lease_until < now()
      ORDER BY shard_id
      LIMIT :want
      FOR UPDATE SKIP LOCKED) free
WHERE sl.shard_id = free.shard_id
RETURNING sl.shard_id, sl.generation;

-- 1c release surplus shards (rebalancing after a new watcher joins); only after the local pollers for them have stopped
UPDATE shard_leases SET lease_until = 'epoch'
WHERE owner_node = :me AND shard_id = ANY(:release::smallint[]);
```

A watcher stops polling a shard as soon as `lease_until − 3s` passes without a successful renewal.

Shard leases only **reduce contention**. Duplicate firing is already prevented by the occurrence unique index and row locks (HLD §5), so claim statements do not re-check the lease generation.

## 2. Watcher: fire due schedules (every ~1s per shard group, and on NOTIFY)

The watcher splits its shards into groups of 8, giving roughly 32 groups across 3 watchers, and runs one transaction per group:

```sql
-- 2a claim due schedules fairly per shard (index: schedules_due (shard_id, next_fire_at) WHERE status = 1)
SELECT d.*, jv.spec AS job_spec
FROM unnest(:shards::smallint[]) AS sh(shard_id)
CROSS JOIN LATERAL (
    SELECT s.schedule_id, s.target_type, s.tenant_id, s.endpoint_id, s.shard_id, s.next_fire_at,
           s.kind, s.cron_expr, s.timezone, s.interval_ms, s.spread_ms, s.dst_policy,
           s.misfire_policy, s.max_catchup, s.definition_version, s.priority, s.last_fired_local
    FROM schedules s
    WHERE s.shard_id = sh.shard_id AND s.status = 1
      AND s.next_fire_at <= now() + make_interval(secs => :lookaheadSec)
    ORDER BY s.next_fire_at
    LIMIT :perShardLimit
    FOR UPDATE OF s SKIP LOCKED
) d
LEFT JOIN job_versions jv ON d.target_type = 1 AND jv.job_id = d.schedule_id AND jv.version = d.definition_version;
```

`FirePlanner` (pure Java) then works out, for each row:
- the occurrence(s) and the misfire decision;
- the next fire time, computed in local time with the DST policy;
- `due_at = scheduled_at + spread`;
- the in-flight rows to create. For a workflow, that is one row per task, plus a `workflow_runs` row.

```sql
-- 2b insert in-flight rows (QUEUED for jobs and DAG roots, WAITING for other tasks; DELAYED for paced backfill)
INSERT INTO run_inflight (shard_id, run_id, tenant_id, target_type, definition_id, definition_version, target_id,
                          task_key, endpoint_id, endpoint_version, scheduled_at, due_at, state, series_started_at,
                          remaining_deps, owner_node, priority, trigger, max_lateness_ms)
SELECT v.shard_id, v.run_id, v.tenant_id, v.target_type, v.definition_id, v.definition_version, v.target_id,
       v.task_key, v.endpoint_id, v.endpoint_version, ts(v.scheduled_us), ts(v.due_us), v.state, now(),
       v.remaining_deps, CASE WHEN v.state = 2 THEN :me END, v.priority, v.trigger, v.max_lateness_ms
FROM unnest(:shard_ids::smallint[], :run_ids::uuid[], :tenant_ids::uuid[], :target_types::smallint[],
            :definition_ids::uuid[], :definition_versions::int[], :target_ids::uuid[], :task_keys::text[],
            :endpoint_ids::uuid[], :endpoint_versions::int[], :scheduled_us::bigint[], :due_us::bigint[],
            :states::smallint[], :remaining_deps::smallint[], :priorities::smallint[], :triggers::smallint[],
            :max_lateness::int[])
     AS v(shard_id, run_id, tenant_id, target_type, definition_id, definition_version, target_id, task_key,
          endpoint_id, endpoint_version, scheduled_us, due_us, state, remaining_deps, priority, trigger,
          max_lateness_ms)
ON CONFLICT (shard_id, target_id, task_key, scheduled_at) DO NOTHING
RETURNING run_id;

-- 2c workflow runs (only when workflows fired in this batch)
INSERT INTO workflow_runs (workflow_run_id, workflow_id, workflow_version, tenant_id, shard_id, scheduled_at, trigger, status, total)
SELECT * FROM unnest(:wfr_ids::uuid[], :wf_ids::uuid[], :wf_versions::int[], :tenant_ids::uuid[],
                     :shard_ids::smallint[], (SELECT array_agg(ts(x)) FROM unnest(:scheduled_us::bigint[]) x),
                     :triggers::smallint[], :statuses::smallint[], :totals::smallint[])
ON CONFLICT (workflow_id, scheduled_at) DO NOTHING;

-- 2d advance recurring schedules
UPDATE schedules s
SET next_fire_at = ts(v.next_us), last_fired_local = v.last_local
FROM unnest(:ids::uuid[], :next_us::bigint[], :last_local::timestamp[]) AS v(id, next_us, last_local)
WHERE s.schedule_id = v.id;

-- 2e retire one-time schedules
DELETE FROM schedules WHERE schedule_id = ANY(:done_ids::uuid[]);
```

**2f.** In the same transaction, insert one **dispatch outbox row** (§0) for each QUEUED run returned by **2b**, with `available_at = due_at`. Then `COMMIT`. The relay (§4c) publishes each row when it is due.

- Rows that `ON CONFLICT` skipped are already in flight from an earlier fire, and are ignored.
- The DELAYED backfill rows are picked up later by §3.

## 3. Watcher: due DELAYED runs (retries, deferrals, backfill, bulk redrive), every ~1s

```sql
WITH due AS (
    SELECT shard_id, run_id FROM run_inflight
    WHERE shard_id = ANY(:shards::smallint[]) AND state = 4
      AND due_at <= now() + make_interval(secs => :lookaheadSec)
    ORDER BY due_at
    LIMIT :batch
    FOR UPDATE SKIP LOCKED)
UPDATE run_inflight r
SET state = 2, fence = r.fence + 1, owner_node = :me
FROM due
WHERE r.shard_id = due.shard_id AND r.run_id = due.run_id
RETURNING r.*;
-- + dispatch outbox rows (§0) for the returned runs, available_at = due_at, in the same transaction
```

The request data for these messages comes from one follow-up read in the same transaction:
- **jobs:** `SELECT job_id, version, spec FROM job_versions WHERE (job_id, version) IN (SELECT * FROM unnest(:ids::uuid[], :versions::int[]))`
- **workflow tasks:** the `WorkflowCatalog` cache (keyed by `workflow_id` and version; versions are immutable)

## 4. Watcher: outbox relay and recovery sweepers (own shards only)

```sql
-- 4c OUTBOX RELAY (every ~100 ms per shard group; immediately again if the batch was full)
--    one transaction: lock due rows -> publish to Kafka (await acks=all) -> delete -> commit
SELECT shard_id, outbox_id, topic, msg_key, payload, trace_parent
FROM outbox
WHERE shard_id = ANY(:shards::smallint[]) AND available_at <= now()
ORDER BY available_at
LIMIT :batch                      -- 2000
FOR UPDATE SKIP LOCKED;
-- (Java) producer.send(...) for every row, then wait for all acknowledgements; on any failure: ROLLBACK (rows stay)
DELETE FROM outbox o
USING unnest(:shard_ids::smallint[], :outbox_ids::uuid[]) AS v(shard_id, outbox_id)
WHERE o.shard_id = v.shard_id AND o.outbox_id = v.outbox_id;
-- COMMIT. If the process dies after the acknowledgements but before COMMIT, the rows are published again:
-- at-least-once. Consumers reject the duplicate (Redis lock + fence, or the event_id for events).

-- 4a stuck-QUEUED safety net (every 60s): a QUEUED run more than 10 minutes past due with no outbox row left
--    (it should never happen; it guards against bugs). A new fence and a new dispatch row.
WITH stuck AS (
    SELECT r.shard_id, r.run_id FROM run_inflight r
    WHERE r.shard_id = ANY(:shards::smallint[]) AND r.state = 2 AND r.due_at < now() - interval '10 minutes'
      AND NOT EXISTS (SELECT 1 FROM outbox o WHERE o.shard_id = r.shard_id AND o.run_id = r.run_id)
    LIMIT :batch
    FOR UPDATE OF r SKIP LOCKED)
UPDATE run_inflight r SET owner_node = :me, fence = r.fence + 1
FROM stuck WHERE r.shard_id = stuck.shard_id AND r.run_id = stuck.run_id
RETURNING r.*;            -- + dispatch outbox rows (§0); metric kairos_stuck_queued_total (alerted)

-- 4b expired leases (every 5s): the executor died or was partitioned. Lock the candidates first:
SELECT r.* FROM run_inflight r
WHERE r.shard_id = ANY(:shards::smallint[]) AND r.state = 3
  AND r.lease_until < now() - interval '30 seconds'          -- safety margin beyond the Redis lock TTL
LIMIT :batch
FOR UPDATE SKIP LOCKED;
```

**4b decision per locked run** (Java, `LeaseSweeper` together with `RunLockService`; HLD §9):

1. `EXISTS kairos:lock:run:{run_id}` → **skip**, because the holder is still alive and slow. Re-check on the next sweep.
2. `GET kairos:sent:{run_id}:{attempt}:{infra}`:
   - **absent**, and Redis has not failed over within the last 310s → the request never left. Requeue as an infra retry: the result is `INFRA / EXECUTOR_LOST`.
   - **present**, or a failover quarantine is active → the outcome is unknown. The result is `UNKNOWN_OUTCOME`, and the endpoint's `unknownOutcomePolicy` applies: RETRY gives a `RetryAt` decision (it uses an attempt); REVIEW gives a `DeadLetter(UNKNOWN_OUTCOME)` decision.
3. The synthetic results go through `SettlementPlanner` and `SettlementService` in the same transaction. The resulting dispatch rows are written to `outbox` (§0).
4. If Redis is unreachable, the sweep is skipped. It never guesses.

## 5. Executor: claim a poll batch (one statement per batch)

```sql
UPDATE run_inflight r
SET state = 3,
    owner_node = :me,
    fence = r.fence + 1,
    lease_until = now() + make_interval(secs => v.lease_secs)
FROM unnest(:shard_ids::smallint[], :run_ids::uuid[], :fences::int[], :lease_secs::int[])
     AS v(shard_id, run_id, fence, lease_secs)
WHERE r.shard_id = v.shard_id AND r.run_id = v.run_id
  AND r.state = 2 AND r.fence = v.fence AND NOT r.cancel_requested
RETURNING r.shard_id, r.run_id, r.fence, r.attempt, r.infra_attempt;
```

- **Before this statement, the executor already holds the Redis run lock** for every run in the batch (`SET NX PX`, pipelined; part 04 §3.2). Runs whose lock was busy are not in the arrays.
- Messages not returned **lost the claim**: they are duplicates, stale fences or cancelled runs. They are acknowledged and dropped (metric `claim_total{result="lost"}`).
- `lease_secs = ceil(timeout_ms / 1000) + 10`, where the timeout comes from the pinned endpoint version.

**DAG inputs** for the claimed workflow tasks in the batch use one read, with partition pruning on `scheduled_at`:

```sql
SELECT o.workflow_run_id, o.task_key, o.outputs, o.sealed
FROM task_outputs o
JOIN unnest(:sched_us::bigint[], :wfr_ids::uuid[], :task_keys::text[]) AS n(sched_us, wfr, task_key)
  ON o.scheduled_at = ts(n.sched_us) AND o.workflow_run_id = n.wfr AND o.task_key = n.task_key;
```

## 6. State processor: settle a result batch (one transaction per Kafka poll)

```sql
-- 6a lock the runs these results belong to (fence-checked). Results with no row returned are stale or replays.
SELECT r.* FROM run_inflight r
JOIN unnest(:shard_ids::smallint[], :run_ids::uuid[], :fences::int[]) AS v(shard_id, run_id, fence)
  ON r.shard_id = v.shard_id AND r.run_id = v.run_id AND r.fence = v.fence AND r.state = 3
ORDER BY r.shard_id, r.run_id          -- deterministic lock order: no deadlocks between processors
FOR UPDATE OF r;
```

`SettlementPlanner.plan(locked rows, results, policies, now)` returns a `SettlementPlan`. `SettlementService` then runs these statements in order. Each one is skipped when its list is empty.

```sql
-- 6b finished runs leave the live table
DELETE FROM run_inflight r
USING unnest(:shard_ids::smallint[], :run_ids::uuid[]) AS v(shard_id, run_id)
WHERE r.shard_id = v.shard_id AND r.run_id = v.run_id;

-- 6c retries and deferrals: back to DELAYED (attempt +1 only for real retries)
UPDATE run_inflight r
SET state = 4, due_at = ts(v.due_us), attempt = v.attempt, deferrals = v.deferrals,
    owner_node = NULL, lease_until = NULL
FROM unnest(:shard_ids::smallint[], :run_ids::uuid[], :due_us::bigint[], :attempts::smallint[], :deferrals::int[])
     AS v(shard_id, run_id, due_us, attempt, deferrals)
WHERE r.shard_id = v.shard_id AND r.run_id = v.run_id;

-- 6d attempt history (append-only; ON CONFLICT makes replays harmless)
INSERT INTO run_history (scheduled_at, run_id, attempt, infra_attempt, tenant_id, target_type, definition_id,
                         target_id, task_key, endpoint_id, trigger, outcome, error_class, final, http_status,
                         started_at, finished_at, latency_ms, executor_id, error, response_snippet)
SELECT ts(v.sched_us), v.run_id, v.attempt, v.infra_attempt, v.tenant_id, v.target_type, v.definition_id,
       v.target_id, v.task_key, v.endpoint_id, v.trigger, v.outcome, v.error_class, v.final, v.http_status,
       ts(v.started_us), ts(v.finished_us), v.latency_ms, v.executor_id, v.error, v.snippet
FROM unnest(:sched_us::bigint[], :run_ids::uuid[], :attempts::smallint[], :infra::smallint[], :tenant_ids::uuid[],
            :target_types::smallint[], :definition_ids::uuid[], :target_ids::uuid[], :task_keys::text[],
            :endpoint_ids::uuid[], :triggers::smallint[], :outcomes::smallint[], :error_classes::smallint[],
            :finals::boolean[], :http_statuses::smallint[], :started_us::bigint[], :finished_us::bigint[],
            :latencies::int[], :executor_ids::text[], :errors::text[], :snippets::text[])
     AS v(sched_us, run_id, attempt, infra_attempt, tenant_id, target_type, definition_id, target_id, task_key,
          endpoint_id, trigger, outcome, error_class, final, http_status, started_us, finished_us, latency_ms,
          executor_id, error, snippet)
ON CONFLICT DO NOTHING;

-- 6e dead letters (same column-array pattern; ON CONFLICT (run_id, attempt, infra_attempt) DO NOTHING)

-- 6f DAG outputs of succeeded tasks
INSERT INTO task_outputs (scheduled_at, workflow_run_id, task_key, tenant_id, outputs, sealed)
SELECT ts(v.sched_us), v.wfr, v.task_key, v.tenant_id, v.outputs, v.sealed
FROM unnest(:sched_us::bigint[], :wfr_ids::uuid[], :task_keys::text[], :tenant_ids::uuid[],
            :outputs::jsonb[], :sealed::bytea[]) AS v(sched_us, wfr, task_key, tenant_id, outputs, sealed)
ON CONFLICT DO NOTHING;

-- 6g DAG: unblock children (one row per (child, decrement)); children reaching 0 become QUEUED
UPDATE run_inflight r
SET remaining_deps = r.remaining_deps - v.dec,
    state      = CASE WHEN r.remaining_deps - v.dec = 0 THEN 2 ELSE r.state END,
    fence      = CASE WHEN r.remaining_deps - v.dec = 0 THEN r.fence + 1 ELSE r.fence END,
    owner_node = CASE WHEN r.remaining_deps - v.dec = 0 THEN :me ELSE r.owner_node END,
    due_at     = CASE WHEN r.remaining_deps - v.dec = 0 THEN now() ELSE r.due_at END
FROM unnest(:shard_ids::smallint[], :wfr_ids::uuid[], :task_keys::text[], :sched_us::bigint[], :decs::smallint[])
     AS v(shard_id, wfr, task_key, sched_us, dec)
WHERE r.shard_id = v.shard_id AND r.target_id = v.wfr AND r.task_key = v.task_key
  AND r.scheduled_at = ts(v.sched_us) AND r.state = 1
RETURNING r.*;     -- rows now in state 2 are published after commit

-- 6h DAG: fail-fast / review-discard removes WAITING descendants (history rows UPSTREAM_FAILED inserted via 6d)
DELETE FROM run_inflight r
USING unnest(:shard_ids::smallint[], :wfr_ids::uuid[], :task_keys::text[], :sched_us::bigint[]) AS v(shard_id, wfr, task_key, sched_us)
WHERE r.shard_id = v.shard_id AND r.target_id = v.wfr AND r.task_key = v.task_key
  AND r.scheduled_at = ts(v.sched_us) AND r.state = 1;

-- 6i workflow run counters and status (results are keyed by workflow_run_id, so one consumer owns each run)
UPDATE workflow_runs w
SET succeeded = w.succeeded + v.succ, failed = w.failed + v.fail, cancelled = w.cancelled + v.canc,
    skipped = w.skipped + v.skip, status = v.status, blocked_until = ts(v.blocked_us),
    finished_at = CASE WHEN v.status IN (3, 4, 5) THEN now() END
FROM unnest(:wfr_ids::uuid[], :succ::smallint[], :fail::smallint[], :canc::smallint[], :skip::smallint[],
            :statuses::smallint[], :blocked_us::bigint[])
     AS v(wfr, succ, fail, canc, skip, status, blocked_us)
WHERE w.workflow_run_id = v.wfr;

-- 6j job health: read only the rows that exist for jobs with a final outcome in this batch
SELECT job_id, recent_mask, recent_count, consecutive_dead, unhealthy_since
FROM job_health WHERE job_id = ANY(:final_job_ids::uuid[]) FOR UPDATE;
-- planner computes new masks; healthy-and-zero rows are deleted, changed rows upserted:
INSERT INTO job_health (job_id, tenant_id, recent_mask, recent_count, consecutive_dead, unhealthy_since, updated_at)
SELECT v.job_id, v.tenant_id, v.mask, v.cnt, v.dead, ts(v.unhealthy_us), now()
FROM unnest(:job_ids::uuid[], :tenant_ids::uuid[], :masks::smallint[], :counts::smallint[], :deads::smallint[],
            :unhealthy_us::bigint[]) AS v(job_id, tenant_id, mask, cnt, dead, unhealthy_us)
ON CONFLICT (job_id) DO UPDATE SET recent_mask = EXCLUDED.recent_mask, recent_count = EXCLUDED.recent_count,
    consecutive_dead = EXCLUDED.consecutive_dead, unhealthy_since = EXCLUDED.unhealthy_since, updated_at = now();
DELETE FROM job_health WHERE job_id = ANY(:healthy_job_ids::uuid[]);

-- 6k auto-pause (consecutive_dead reached jobs.spec.autoPauseAfter) and one-time job completion
UPDATE jobs SET status = v.status, updated_at = now()
FROM unnest(:job_ids::uuid[], :statuses::text[]) AS v(job_id, status)
WHERE jobs.job_id = v.job_id AND jobs.status = 'ACTIVE';
UPDATE schedules SET status = 2 WHERE schedule_id = ANY(:auto_paused_job_ids::uuid[]);
```

**6l.** In the same transaction, insert the plan's **outbox rows** (§0): dispatches for newly QUEUED DAG children, `run.dead` events for every dead-lettered run, `run.completed` events (tenants that opted in), and workflow, job and endpoint events. Then `COMMIT` → acknowledge the Kafka batch.

- **Replays.** A result whose row was not returned by **6a** is either already settled (its events were written to the outbox in the original transaction) or a stale fence. Either way it is counted and dropped. **No re-emission logic is needed any more.**
- **Healthy path cost.** A batch of successes with no recent failures runs only **6a, 6b, 6d**, and the **6j** read finds nothing.

## 7. API-side transactions

```sql
-- 7a immediate job / run-now: insert QUEUED + its dispatch outbox row (available_at = now()) in the same tx as the job
--     (same INSERT as 2b with trigger = 2 IMMEDIATE or 3 MANUAL)

-- 7b cancel a live run that is not RUNNING
DELETE FROM run_inflight
WHERE shard_id = :shard AND run_id = :run AND tenant_id = :tenant AND state IN (1, 2, 4)
RETURNING *;                                  -- + history row CANCELLED (final) in the same tx
DELETE FROM outbox WHERE shard_id = :shard AND run_id = :run AND topic IN (1, 2, 3);   -- unpublished dispatch, if any

-- 7c cancel a RUNNING run
UPDATE run_inflight SET cancel_requested = true
WHERE shard_id = :shard AND run_id = :run AND tenant_id = :tenant AND state = 3
RETURNING fence;                              -- + CancelMessage(run, fence) outbox row (topic 4) in the same tx

-- 7d redrive a DLQ entry (same run_id keeps the target's Idempotency-Key stable)
INSERT INTO run_inflight (shard_id, run_id, tenant_id, target_type, definition_id, definition_version, target_id,
                          task_key, endpoint_id, endpoint_version, scheduled_at, due_at, state, attempt,
                          series_start, series_started_at, infra_attempt, priority, trigger, owner_node)
SELECT d.shard_id, d.run_id, d.tenant_id, d.target_type, d.definition_id,
       CASE WHEN :mode = 2 THEN :current_def_version ELSE d.definition_version END,
       d.target_id, d.task_key, d.endpoint_id,
       CASE WHEN :mode = 2 THEN :current_ep_version ELSE d.endpoint_version END,
       d.scheduled_at, now() + make_interval(secs => :offsetSec), 4, d.attempt + 1, d.attempt + 1, now(), 0,
       d.priority, 5, NULL
FROM dead_letters d
WHERE d.dlq_id = :dlq AND d.tenant_id = :tenant AND d.status IN (1, 2)
ON CONFLICT (shard_id, target_id, task_key, scheduled_at) DO NOTHING
RETURNING run_id;
UPDATE dead_letters SET status = 3, resolved_by = :actor, resolved_at = now(), redrive_mode = :mode
WHERE dlq_id = :dlq AND tenant_id = :tenant AND status IN (1, 2);
-- + workflow_runs BLOCKED -> RUNNING when this was the only dead task; + audit_log row
```

- **Bulk redrive** runs 7d over a filtered set in pages of 500.
- `:offsetSec` spaces the rows at the requested `ratePerSec` (row *i* gets `i / rate`). The watcher's DELAYED poller (§3) then dispatches them at the paced rate.

## 8. Singleton tasks (watcher, guarded by `pg_try_advisory_xact_lock`)

```sql
-- 8a auto-suspend evaluation (every 15s): last 5 minutes, all writer nodes summed
SELECT h.endpoint_id, h.tenant_id,
       sum(h.attempts) attempts, sum(h.successes) successes, sum(h.dead_lettered) dead
FROM endpoint_health_minute h
JOIN api_endpoints e ON e.endpoint_id = h.endpoint_id AND e.status = 'ACTIVE'
WHERE h.minute >= date_trunc('minute', now()) - interval '5 minutes'
GROUP BY h.endpoint_id, h.tenant_id;
-- AutoSuspendRule (domain) applies each endpoint's suspendPolicy thresholds; for each endpoint to suspend, in one tx:
UPDATE api_endpoints SET status = 'SUSPENDED', status_reason = :reason, status_changed_at = now()
WHERE endpoint_id = :ep AND status = 'ACTIVE';
UPDATE schedules SET status = 3 WHERE endpoint_id = :ep AND status = 1;
INSERT INTO audit_log (tenant_id, actor, action, resource_type, resource_id, details)
VALUES (:tenant, 'system:auto-suspend', 'ENDPOINT_SUSPENDED', 'endpoint', :ep::text, :details::jsonb);

-- 8b resume (API, manual): status ACTIVE; schedules 3 -> 1. Misfire policy then applies to overdue next_fire_at.

-- 8c DAG review timeout (every 60s): BLOCKED runs past blocked_until are failed through SettlementPlanner
SELECT workflow_run_id FROM workflow_runs WHERE status = 2 AND blocked_until < now() LIMIT 100 FOR UPDATE SKIP LOCKED;
```

## 9. Known performance trade-offs, measured in M2

| Concern | Expectation | Fallback if measurements disagree |
|---|---|---|
| A claim on `run_inflight` is a non-HOT update (partial indexes on `state`) | Fine while the table is small and in memory | Move lease fields into a separate `run_leases` table (insert at claim, delete at settle) |
| `schedules.next_fire_at` churn at 50K updates/s | Autovacuum keeps up on a narrow table | Raise `max_catchup` pacing; spread shards across 2 databases (HLD §13) |
| Parameterised join of `unnest` arrays to the hash-partitioned table | Runtime partition pruning inside nested loops | Group the batch by partition in Java and issue one statement per partition |
| Claim `SELECT` with `LATERAL … FOR UPDATE SKIP LOCKED` | Index-ordered per shard | One query per shard |
| Outbox insert and delete churn (about 50K rows/s at peak) | Small table, parallel vacuum across 16 partitions | Larger relay batches; one statement per partition |
