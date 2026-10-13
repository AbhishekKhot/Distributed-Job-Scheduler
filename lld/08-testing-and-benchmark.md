# Kairos LLD Part 08: Testing and Benchmark

Back to [LLD index](../LLD.md).

## 1. Test strategy per module

| Module | Test types | Key cases |
|---|---|---|
| `kairos-domain` | JUnit 5 + AssertJ; **jqwik** property tests; PIT mutation testing (target ≥ 80% mutation score) | <ul><li>`NextFireCalculator`: every zone, DST transitions 2020–2035, `ONCE`/`SHIFT` → exactly one fire per local day; `INTERVAL` has no drift</li><li>`BackoffStrategy`: bounds and monotonic caps</li><li>`RetryPolicy`: `maxAge`, `Retry-After` precedence</li><li>`SettlementPlanner`: model-based tests (random sequences of results, invariants: no illegal transition, exactly one final history row per series, DLQ iff policy says so)</li><li>`OutcomeClassifier`: the full HLD §14.2 table plus overrides</li><li>`DagValidator`: cycles, depth, ancestor-only references</li><li>`TemplateParser`/`Renderer`: fuzzed inputs never throw unchecked exceptions; rendered JSON always parses; headers never contain CR/LF</li><li>`JsonPathLite`: grammar, limits</li><li>`EgressPolicy`: IPv4, IPv6, mapped, NAT64, CIDR edges, suffix label boundaries</li><li>`JobHealth`: mask arithmetic</li><li>`AutoSuspendRule`: thresholds and cool-down</li></ul> |
| `kairos-persistence` | Testcontainers Postgres 18 with Flyway | <ul><li>Every SQL statement in part 02, run against real data</li><li>**Concurrent claim test:** 8 threads claiming the same 10K due schedules → each occurrence inserted exactly once</li><li>Fenced claim and settle under races</li><li>`ON CONFLICT` replays</li><li>DAG counter decrements from concurrent parents</li><li>Partition create and drop</li><li>Role grants: each role user is denied operations outside its grant list</li><li>**Cells:** two cell databases; routing by shard-embedded ids; per-cell settle with one cell failing (the other commits; the retry is idempotent); fan-out list merge and cursor stability; startup refuses a mismatched cell map</li></ul> |
| **Outbox and lock** | Testcontainers Postgres + Kafka + **Redis** | <ul><li>Outbox: a crash before commit (rows survive and republish); Kafka down (rows accumulate, nothing is lost); a shard move hands rows to the new relay; a cancel deletes an unpublished dispatch</li><li>Lock: the Lua acquire and release table (first acquire, duplicate fence busy, stale fence busy, newer fence takeover, owner-only release, numeric fence comparison); TTL expiry</li><li>Send guard aborts when time runs out</li><li>The sent marker is always written before the send (verified by a fake HTTP server recording the order)</li><li>Lease sweeper decisions for no marker, marker present, lock alive, quarantine active</li><li>Redis down → consumers pause, 0 executions; Redis back → resume</li></ul> |
| `kairos-messaging` | Unit + Testcontainers Kafka | <ul><li>`OffsetTracker`: out-of-order completion, revoke mid-flight</li><li>Pause and resume backpressure</li><li>Static-membership restart causes no rebalance</li><li>Poison records reach the quarantine topic</li></ul> |
| `kairos-security` | Unit + integration | <ul><li>API key parse, verify, revoke, expiry, pepper rotation; timing-safe comparison used</li><li>Tink round trips with AAD mismatch rejected</li><li>Signature vectors</li><li>`ValidatingAddressResolverGroup` against a stub DNS that returns private, metadata and rebinding answers</li></ul> |
| Identity (`kairos-api`) | Unit + Testcontainers | <ul><li>Argon2id hash and verify, rehash on parameter change</li><li>Signup with an existing email gives the same response as a new one; disallowed domains are rejected</li><li>Login lockout progression and equal timing for unknown emails</li><li>Refresh rotation; **reuse of a rotated token revokes the family**</li><li>Logout and password change revoke sessions; access tokens of revoked sessions stop working within 30s</li><li>Invitations: wrong email, expired, reused tokens rejected</li><li>Ownership safeguards: the last owner cannot be removed or demoted</li><li>**Authorization matrix**: every endpoint × every role (and API keys with and without the scope) returns the expected 2xx, 403 or 404</li><li>Cross-organization access returns `404` for every resource type</li></ul> |
| `kairos-http` | Netty test server with TLS (test CA) | <ul><li>Timeouts</li><li>Bounded body reads</li><li>Redirects off</li><li>Hostname verification fails on a wrong certificate</li><li>Every auth provider, including OAuth2 refresh single-flight</li><li>Bulkhead, rate limit and breaker → `DEFERRED`</li></ul> |
| Services (`kairos-api`, `kairos-watcher`, `kairos-executor`, `kairos-state`) | Spring slice tests (`@WebMvcTest` + security); a full `@SpringBootTest` per service with Testcontainers | <ul><li>Security: no credential or a bad one → 401 (generic body); missing role or scope → 403; another organization's resource → 404; unknown JSON field → 400; over size → 413; rate limit → 429 with `Retry-After`; idempotent replay and conflicting reuse → 422</li><li>Problem responses never contain input values or stack traces</li><li>Security headers present</li><li>OpenAPI snapshot</li></ul> |
| Architecture | ArchUnit | Library and service boundaries (services never depend on each other); no `Statement` or SQL concatenation; repositories require `TenantId`; domain free of framework classes; no `ObjectInputStream`; no `java.util.Random` in security packages |
| E2E | Compose + `bench-cli` | S1-lite, S3-lite, S6-lite and S12 in CI on the main branch (part 07 §5) |
| Chaos | `kind` + scripts, Toxiproxy | S5 (§3) |

## 2. Benchmark tooling

### 2.1 `mock-target` (raw Netty, HTTPS)

| Feature | Detail |
|---|---|
| Behaviour per path | `/{scenario}/{bucket}`. The scenario file (YAML, loaded at start or swapped through `POST /admin/scenario` with an admin token from an environment variable) defines a latency distribution (fixed, uniform, log-normal with p50 and p99) and an error mix: `503` with an optional `Retry-After`, `429`, `400`, hang-until-timeout, slow body, oversize body. |
| Outputs | For DAG tests: `/dag/login` returns `{customerId, access_token}`; `/dag/order/{customerId}` checks `Authorization: Bearer <expected>` and returns `{data: {id}}` plus an `ETag`; `/dag/notify` checks that it received `orderId` and `etag`. |
| **Overlap detection** | Tracks in-flight requests per `run_id` (a concurrent map of start counts). A second request for a `run_id` that is still being served increments `concurrent_duplicate_total`, **which must stay 0**. |
| Receipts | Each request appends a 40-byte record (`run_id` 16 B, attempt 2 B, flags 2 B, `receivedAt` 8 B, `scheduledAt` 8 B, latency 4 B) to a memory-mapped receipt log per core. That is about 360 MB for 9M receipts, with no per-key maps in memory. |
| Live statistics | HdrHistogram of `received − X-Kairos-Scheduled-At` (due); requests per second; error mix → `/metrics` (Prometheus) and `/stats` |
| Signature check | Verifies `X-Kairos-Signature` on every request (counts failures), proving the signing path under load |

### 2.2 `bench-cli` (picocli)

```
bench seed     --tenant bench --endpoint bench-target --jobs 3000000 --type INTERVAL --every 60s
               --spread uniform --via api|copy [--batch 1000]
bench run      --scenario bench/scenarios/s1.yaml [--env compose|kind]
bench verify   --from <ts> --to <ts> [--receipts dir] --out report.json
bench report   --in report.json --out bench/reports/<scenario>-<timestamp>.md
bench chaos    --plan bench/scenarios/s5-chaos.yaml        # uses ProcessBuilder with argument arrays (no shell)
```

**`seed --via copy`** is test-only. It uses `sched_bench` with `COPY` into `jobs`, `job_versions` and `schedules`, which is about 100× faster than going through the API. `--via api` exercises `POST /jobs:batch`.

**`verify` algorithm:**

1. **Expected occurrences.** From the seeded schedules, computed arithmetically for INTERVAL jobs and enumerated for cron jobs: the set of `(job_id, scheduled_at)` in the window, excluding the warm-up and cool-down margins.
2. **Recorded.** `COPY (SELECT … FROM run_history WHERE scheduled_at BETWEEN …) TO STDOUT`, streamed and sorted by `run_id`.
3. **Received.** The mock-target receipt logs, merged and sorted externally by `run_id`.
4. **Checks:**

| Check | Pass condition |
|---|---|
| Lost | expected − final history rows = **0** (excluding the documented in-flight tail at the window end) |
| **Concurrent duplicates** | `concurrent_duplicate_total` from the mock target = **0** |
| **Re-sends** | Receipts with the same `run_id` and a different attempt, explained by `UNKNOWN_OUTCOME` history rows (RETRY policy) **only**. Under `REVIEW`, there must be none. Re-sends without a matching unknown-outcome record = **0**. |
| Retry spacing | For each failed attempt sequence, the gaps fall inside the policy bounds |
| DLQ | `dead_letters` count = injected permanent failures + exhausted retryables, exactly |
| Lag | p50/p95/p99/max from the target histogram and the executor `kairos_schedule_lag_seconds` |
| Throughput | Completed runs per second, in 1-minute windows (minimum, median, maximum) |

**Report.** Markdown and JSON containing: environment (hardware, Docker resources, versions, configuration), scenario, the pass/fail table, a per-component rate table, a throughput timeline (CSV), the lag distribution, database statistics (`pg_stat_statements` top 10 by total time, WAL bytes per second, dead tuples) and Kafka lag.

### 2.3 k6 scripts (`bench/k6/`)

| Script | Load |
|---|---|
| `api-create.js` | Ramp `POST /jobs` |
| `api-batch.js` | `POST /jobs:batch` with 1,000 items |
| `api-read.js` | Status and history reads |
| `api-redrive.js` | Bulk redrive with dry run |

The API key comes from the `KAIROS_API_KEY` environment variable. Thresholds: p99 < 200 ms for reads and < 500 ms for creates, error rate < 0.1%.

## 3. Scenarios (`bench/scenarios/*.yaml`)

Each scenario has a **local** profile (Compose) and a **perf** profile (a dedicated environment sized for the target rate). Local profiles use the same shape at a lower rate.

| ID | Setup (perf profile) | Pass criteria |
|---|---|---|
| **S1** steady state | 1.2M INTERVAL jobs every 60s, giving 20K/s, for 15 minutes. Locally, the rate is ramped in steps (5 minutes each) until a criterion fails, which records the environment's ceiling. | Lag p99 ≤ 2s; 0 lost; duplicates reported; throughput sustained |
| S2 burst | N one-time jobs due in the same second (N = 5 × the measured ceiling) | Drain time ≈ N / ceiling; 0 lost |
| **S3** retries and DLQ | 30% of the ceiling; 5% `503`, 2% `429` with `Retry-After: 5`, 1% `400`, 1% hang | Retry spacing within policy (30, 60, 120, 240, 480s ± jitter); each `400` dead-lettered after one attempt; exact DLQ count; 0 lost |
| **S4** endpoint outage | 2 endpoints; endpoint A returns `503` for 10 minutes | A is suspended within about a minute of the failure-ratio trigger; its DLQ growth stops; B is unaffected (lag p99 ≤ 2s); after resume, misfires replay; 0 lost |
| S5 chaos (`kind`) | 2K/s; kill a watcher, executor and state pod every 2 minutes; drain a node; Toxiproxy adds 200 ms of database latency for 1 minute | 0 lost; recovery ≤ 20s; 0 concurrent duplicates |
| **S6** workflows | 1K workflows/min shaped `login → createOrder → (reserveStock, notify)`; 1% of `createOrder` calls return `400` | Every downstream request receives the correct upstream values (checked by the mock target); **no sensitive token appears in Kafka or logs** (dump and scan); blocked runs succeed after redrive |
| S7 API | The k6 scripts above | Thresholds above |
| S8 claim mode | S1 at the ceiling with `STRICT` vs `RELAXED` claims | Report the difference in throughput, database writes and duplicates |
| **S9** duplicate proof | 2K/s for 10 minutes with injected faults: (a) `kill -9` of executors before and after sending (via a test hook); (b) the relay publishing every batch twice; (c) a Redis primary failover; (d) `SIGSTOP` of an executor beyond its lease, then `SIGCONT`; (e) a Kafka broker restart | **0 concurrent duplicates**; no re-send of a request that was never sent; every re-send has an `UNKNOWN_OUTCOME` record and the same `Idempotency-Key`; under `REVIEW`, no re-sends at all; 0 lost |
| **S10** peak | 20K/s base plus a 10-minute ramp to **50K/s**, then back down | Lag p99 ≤ 2s at the provisioned rate; any backlog above it drains after the peak; high-priority lag ≤ low-priority lag; 0 lost; 0 concurrent duplicates |
| **S11** cell outage | 2 or more cells; stop one cell's primary for 2 minutes during S1 | Other cells are unaffected (lag p99 ≤ 2s); the stopped cell's runs resume; 0 lost; 0 concurrent duplicates |
| **S12** onboarding | Scripted end to end: sign up → verify email (Mailpit API) → log in → create organization → invite and accept a member → create an API key → add a HOST egress rule and verify it against the mock target's well-known token → add a secret → register and test an endpoint → create a job and a workflow | Every step returns the expected status; the first run succeeds; a second organization cannot read or call anything of the first (`404` everywhere); revoking the egress rule blocks the next call |

**Bottleneck isolation**, run before S1:

| Run | What runs | Measures |
|---|---|---|
| **B1** | Watcher and relay → Kafka, with executors stopped | Fire and publish rate |
| **B2** | A pre-filled `run.*` topic → executors → mock target, with the state processor stopped | Call rate |
| **B3** | A pre-filled `run.result` topic → state processor | Settle rate |

The lowest of the three is the system ceiling, and it shows which component to tune or scale.

**Capacity projection.** The report includes a projection for the target environment:
- `required_instances = target_rate / measured_rate_per_instance × 1.5` for each role;
- the cell count from `7 × target_rate / measured_cell_write_ceiling`, rounded up.

Projections are labelled as estimates until they are confirmed in the perf environment.
