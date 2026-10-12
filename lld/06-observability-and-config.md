# Kairos LLD Part 06: Observability and Configuration

Back to [LLD index](../LLD.md).

## 1. Metrics (Micrometer, scraped by Prometheus)

**Cardinality rule:** labels may be `tenant`, `endpoint` (endpoint name, a bounded set), `tier`, `error_class`, `component`, `result` and `trigger`. **Never** `job_id`, `run_id` or URLs.

| Metric | Type | Labels | Emitted by |
|---|---|---|---|
| `kairos_fire_total` | counter | `trigger` | watcher |
| `kairos_fire_batch_rows` | summary | — | watcher |
| `kairos_claim_schedules_seconds` | timer | — | watcher |
| `kairos_outbox_backlog` / `kairos_outbox_oldest_due_age_seconds` | gauge | — | watcher (relay) |
| `kairos_outbox_published_total` / `kairos_outbox_relay_batch_seconds` | counter / timer | `topic` | watcher (relay) |
| `kairos_outbox_rows_written_total` | counter | `topic`, `component` | watcher, api, state |
| `kairos_shards_owned` | gauge | — | watcher |
| `kairos_stuck_queued_total` / `kairos_leases_expired_total` | counter | `decision` (lease: `requeue_safe`, `unknown_outcome`, `skipped_lock_alive`) | watcher |
| `kairos_lock_total` | counter | `result=acquired\|took_over\|busy\|error` | executor |
| `kairos_send_guard_aborts_total` / `kairos_unknown_outcomes_total` | counter | `policy` | executor, state |
| `kairos_redis_available` / `kairos_redis_failovers_total` / `kairos_redis_marker_quarantine` | gauge / counter / gauge | — | executor, watcher |
| `kairos_claim_total` | counter | `result=won\|lost` | executor |
| `kairos_claim_batch_seconds` | timer | — | executor |
| **`kairos_schedule_lag_seconds`** | histogram (SLO buckets 0.05 … 30s) | `tier`, `trigger` | executor (`http start − due_at`) |
| `kairos_http_requests_total` | counter | `tenant`, `endpoint`, `error_class` | executor |
| `kairos_http_request_seconds` | histogram | `tenant`, `endpoint` | executor |
| `kairos_inflight_calls` | gauge | `tier` | executor |
| `kairos_endpoint_breaker_state` | gauge (0 closed, 1 half-open, 2 open) | `endpoint` | executor |
| `kairos_endpoint_permit_wait_seconds` | timer | `endpoint` | executor |
| `kairos_settle_batch_seconds` / `kairos_settle_batch_rows` | timer / summary | — | state |
| `kairos_fenced_drops_total` | counter | `component` | executor, state |
| `kairos_result_replays_total` | counter | — | state |
| `kairos_retries_scheduled_total` | counter | `tenant`, `endpoint`, `error_class` | state |
| `kairos_deferrals_total` | counter | `tenant`, `endpoint`, `error_class` | state |
| **`kairos_dlq_entries_total`** | counter | `tenant`, `endpoint`, `reason` | state, watcher |
| `kairos_dlq_open` / `kairos_dlq_oldest_open_seconds` | gauge | `tenant` | watcher singleton |
| `kairos_jobs_auto_paused_total` / `kairos_jobs_unhealthy` | counter / gauge | `tenant` | state / watcher |
| `kairos_endpoint_suspended` | gauge (0/1) | `tenant`, `endpoint` | watcher singleton |
| `kairos_auth_failures_total` | counter | `reason` | api |
| `kairos_rate_limited_total` | counter | `bucket_class` | api |
| Kafka client, JVM, Hikari, Reactor Netty pool, HTTP server | built-in binders | — | all |
| Postgres | `postgres_exporter` (+ `pg_stat_statements`) | — | sidecar / exporter |

**Histogram buckets** for `kairos_schedule_lag_seconds`: `0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30`. The value 2 matches the SLO, so p99 can be read exactly at that boundary.

## 2. Alert rules (Prometheus rules in `deploy/helm/kairos/files/alerts.yaml`)

```yaml
groups:
- name: kairos-slo
  rules:
  - alert: ScheduleLagP99High
    expr: histogram_quantile(0.99, sum by (le) (rate(kairos_schedule_lag_seconds_bucket[5m]))) > 2
    for: 5m
    labels: {severity: critical}
  - alert: DispatchBacklogGrowing
    expr: sum(kafka_consumergroup_lag{consumergroup="kairos-executor"}) > 100000 and deriv(sum(kafka_consumergroup_lag{consumergroup="kairos-executor"})[10m:]) > 0
    for: 10m
    labels: {severity: warning}
  - alert: OutboxBacklog
    expr: max(kairos_outbox_oldest_due_age_seconds) > 10
    for: 2m
    labels: {severity: critical}
  - alert: RedisUnavailable
    expr: min(kairos_redis_available) == 0
    for: 30s
    labels: {severity: critical}
  - alert: UnknownOutcomes
    expr: increase(kairos_unknown_outcomes_total[15m]) > 0
    labels: {severity: warning}
- name: kairos-failures
  rules:
  - alert: SystemFailureRateHigh            # >10% in 1h
    expr: sum(rate(kairos_http_requests_total{error_class!="NONE"}[1h])) / sum(rate(kairos_http_requests_total[1h])) > 0.10
    labels: {severity: warning}
  - alert: DlqTooLarge                      # DLQ > 100
    expr: sum by (tenant) (kairos_dlq_open) > 100
    labels: {severity: warning}
  - alert: DlqEntryUnreviewed
    expr: max by (tenant) (kairos_dlq_oldest_open_seconds) > 86400
    labels: {severity: info}
  - alert: EndpointAutoSuspended
    expr: max by (tenant, endpoint) (kairos_endpoint_suspended) == 1
    labels: {severity: critical}
  - alert: JobUnhealthy                     # a job fails >50% of its last 10 runs
    expr: sum by (tenant) (kairos_jobs_unhealthy) > 0
    labels: {severity: critical}
  - alert: JobAutoPaused
    expr: increase(kairos_jobs_auto_paused_total[10m]) > 0
    labels: {severity: warning}
- name: kairos-capacity
  rules:
  - alert: ExecutorsUnhealthy               # <30% healthy
    expr: sum(up{job="kairos-executor"}) / count(up{job="kairos-executor"}) < 0.3
    labels: {severity: critical}
  - alert: ExecutorCapacityHigh             # capacity >80%
    expr: sum(kairos_inflight_calls) / sum(kairos_executor_inflight_capacity) > 0.8
    for: 10m
    labels: {severity: warning}
  - alert: PostgresXidAgeHigh
    expr: max(pg_database_xid_age) > 1000000000
    labels: {severity: critical}
  - alert: PostgresLongTransaction
    expr: max(pg_stat_activity_max_tx_duration{state="active"}) > 300
    labels: {severity: warning}
  - alert: CanaryMissing                    # dead-man's switch: the canary job increments this every minute
    expr: absent_over_time(kairos_canary_heartbeat_total[3m]) or increase(kairos_canary_heartbeat_total[3m]) == 0
    labels: {severity: critical}
```

- Severity routing: `critical` → PagerDuty; `warning` → Slack; `info` → daily digest. This is set in the Alertmanager configuration, with receiver credentials coming from secrets.
- **The canary.** A system-tenant cron job (every minute) calls `mock-target /canary` locally, or a real external heartbeat service in production. The target increments `kairos_canary_heartbeat_total`.

## 3. Tracing (OpenTelemetry)

| Span | Kind | Key attributes (no payloads, no secrets) |
|---|---|---|
| `watcher.fire_batch` | internal | `kairos.shard_group`, `kairos.rows` |
| `kafka.publish` | producer | `messaging.destination.name` |
| `executor.run` | consumer | `kairos.run_id`, `kairos.attempt`, `kairos.tenant`, `kairos.endpoint` |
| `http.client` | client | HTTP semantic conventions; the URL is reported as `scheme://host/path-template` (no query values) |
| `state.settle_batch` | consumer | `kairos.rows`, `kairos.decisions.*` counts |

- **Propagation.** The W3C `traceparent` travels in Kafka headers and on to the target.
- **Sampling.** Parent-based, 1% by default (`otel.traces.sampler.arg`). The OTel Collector runs a tail-sampling policy that keeps every trace containing an error or a `RETRYABLE` or `NON_RETRYABLE` outcome.

## 4. Logging

- **Format.** Spring Boot structured JSON logging (`logging.structured.format.console=ecs`).
- **Allowed MDC fields:** `traceId`, `spanId`, `tenant`, `runId`, `jobId`, `endpoint`, `nodeId`, `component`.
- **Never logged:** request or response bodies, header values, API keys, secrets, sensitive outputs, `Authorization`.
  - Errors from targets pass through `Redactor` before they are logged or stored.
  - A logback `TurboFilter` drops any message containing `sk_` key-shaped tokens, as a guard against developer mistakes.
- **Levels.** INFO for lifecycle and state changes (lease acquired or lost, endpoint suspended); WARN for retried infrastructure errors; ERROR only for unexpected exceptions. **No per-run INFO logs on the hot path.** Those are covered by metrics and traces.

## 5. Configuration catalogue (`application.yaml`; every value is overridable by environment variable)

| Property | Default | Notes |
|---|---|---|
| `kairos.roles` | `api,watcher,executor,state` | `KAIROS_ROLES`; `maintenance` runs alone |
| `kairos.node.heartbeat-interval` / `dead-after` | `5s` / `15s` | |
| `kairos.shards.count` | `256` | Fixed after the first deploy |
| `kairos.cells[n].id` / `shard-from` / `shard-to` / `datasource` | 1 cell `0–255` locally; reference: 4 cells × 64 shards | Validated at startup against `cell_map`; startup fails on a mismatch |
| `kairos.control.datasource` | from env | Control database |
| `kairos.shards.lease-ttl` / `renew-interval` | `15s` / `5s` | |
| `kairos.watcher.poll-interval` / `jitter` | `1s` / `0.2` | |
| `kairos.watcher.lookahead` | `5s` | |
| `kairos.watcher.shard-group-size` / `per-shard-limit` | `8` / `250` | |
| `kairos.outbox.relay-interval` / `batch` | `100ms` / `2000` | Outbox relay |
| `kairos.outbox.max-dispatch-per-sec` | `0` (unlimited) | Thundering-herd pacing |
| `kairos.outbox.backlog-admission-age` | `10s` | Fire pollers pause when the oldest due row is older than this |
| `kairos.redis.url` / `ssl` / `username` | from env (`REDIS_URL`, `REDIS_USERNAME`); TLS on | Password from `REDIS_PASSWORD` |
| `kairos.lock.grace` | `10s` | Lock TTL = timeout + grace |
| `kairos.lock.send-guard-margin` | `2s` | |
| `kairos.lock.sent-marker-ttl` | `48h` | |
| `kairos.lock.sweeper-margin` | `30s` | Lease sweeper waits `lease_until` + margin |
| `kairos.lock.failover-quarantine` | `310s` | Maximum lease |
| `kairos.execution.unknown-outcome-policy` | `RETRY_WITH_IDEMPOTENCY_KEY` | Per-endpoint override; default **to be confirmed** |
| `kairos.events.completed-enabled` | `false` per tenant | `run.completed` events are opt-in (outbox cost) |
| `kairos.watcher.misfire-threshold` | `60s` | |
| `kairos.watcher.stuck-queued-after` | `10m` | Safety net |
| `kairos.executor.inflight-high-water` / `low-water` | `4000` / `3000` | Per pod |
| `kairos.executor.max-poll-records.{high,medium,low}` | `250` / `150` / `100` | Weighted share |
| `kairos.executor.lease-grace` | `10s` | |
| `kairos.executor.drain-timeout` | `60s` | |
| `kairos.executor.permit-max-wait` | `200ms` | |
| `kairos.executor.connect-timeout` | `3s` | |
| `kairos.executor.pool.max-connections-per-host` / `pending-acquire-max` / `pending-acquire-timeout` | `500` / `2000` / `1s` | |
| `kairos.executor.default-max-response-bytes` | `262144` | |
| `kairos.executor.breaker.window` / `failure-rate` / `open-duration` | `100` / `50` / `30s` | |
| `kairos.state.max-poll-records` | `1000` | |
| `kairos.state.stats-flush-interval` | `10s` | |
| `kairos.retry.default.*` | 5 retries, `EXPONENTIAL_JITTER`, base `30s`, max `10m`, max-age `24h` | HLD §10.3 |
| `kairos.retry.max-infra-attempts` | `3` | |
| `kairos.defer.rate-limit-delay` / `suspended-recheck` | `1s..2s` / `60s` | |
| `kairos.protection.auto-pause-after` | `3` | **On by default** |
| `kairos.protection.auto-suspend.*` | enabled, 50 dead / 5 min, 0.8 ratio with ≥ 200 attempts, re-suspend cool-down `10m` | **On by default** |
| `kairos.dag.max-tasks` / `max-depth` / `review-timeout` | `200` / `10` / `24h` | |
| `kairos.backfill.rate-per-job` / `global-rate` | `100/s` / `1000/s` | |
| `kairos.retention.history` / `dlq-resolved` / `task-outputs` / `audit` | `7d` / `7d` / `7d` / `90d` | |
| `kairos.api.max-batch` / `idempotency-ttl` | `1000` / `24h` | |
| `kairos.api.key-cache-ttl` / `negative-cache-ttl` | `60s` / `10s` | |
| `kairos.ratelimit.*` | see part 05 §3 | |
| `kairos.egress.global-deny-cidrs` / `control-plane-cidrs` / `deny-hostnames` | built-in list / env-specific / built-in list | |
| `kairos.crypto.kek-uri` | `${KAIROS_KEK_URI}` | `gcp-kms://…` or `local-dev:` |
| `spring.datasource.*` / `kairos.datasource.replica.*` | from env | One datasource per role user |
| `spring.kafka.bootstrap-servers` | `${KAFKA_BOOTSTRAP}` | |
| `management.server.port` | `8081` | Probes and metrics, never exposed through the gateway |
| `otel.traces.sampler.arg` | `0.01` | |
