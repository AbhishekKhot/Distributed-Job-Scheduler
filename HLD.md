# Kairos: High-Level Design

## 1. Overview

Kairos is an internal job scheduler. A team registers the internal HTTP APIs it wants to call (**endpoints**), then schedules calls to them as **jobs** (immediate, one-time, cron or interval) or as **workflows**, which are DAGs of calls where a task can use values from its upstream tasks' responses.

Kairos guarantees that:
1. every run that becomes due is executed **at least once**;
2. a run is **never executed by two workers at the same time**, and a completed run is never executed again;
3. when the outcome of a call is unknown, the retry carries the **same idempotency key**, so the target can recognise it.

It is sized for **20K executions/s sustained and 50K/s at peak**, with a p99 start delay of at most 2 seconds. It is a monorepo of four Spring Boot microservices on Kubernetes (GKE), using Java 25, PostgreSQL, Kafka and Redis.

## 2. How it works

![Kairos end-to-end flow](assets/kairos-flow.svg)

| Stage | What happens |
|---|---|
| **A. Onboarding** | A user signs up and logs in with email and password (1). A new user has no team, so they create one or an existing member adds them (2). After a token refresh, the access token carries the team, and the team can create API keys for automation (3). |
| **B. Register and schedule** | The team registers an internal API as an endpoint: URL template, method, authentication, timeout, retry policy and outputs to extract (4). Jobs (5) and workflows (6) reference endpoints by name. A definition is stored in the cell database chosen by its shard, with a `schedules` row holding `next_fire_at`. |
| **C. Dispatch** | Every second, the watcher claims schedules due in the next 5 seconds. In one transaction it inserts each run as `QUEUED`, writes a dispatch row to the `outbox` table and advances `next_fire_at` (7). The outbox relay publishes due rows to the topic for the job's priority tier and deletes them once Kafka acknowledges (8). |
| **D. Execute** | An executor takes a Redis lock for the run, then claims it in the database with a fence check, moving it from `QUEUED` to `RUNNING` (9). It calls the target over HTTPS with `Idempotency-Key` set to the run id (10) and publishes the result. The state processor settles results in batches (11). |
| **E. Outcome** | Success writes a `run_history` row. A retryable failure makes the run `DELAYED` with exponential backoff, and the watcher dispatches it again when it is due (12). After 5 retries (about 15 minutes) the run is dead-lettered: a `dead_letters` entry, an alert and an event. A team member can redrive or discard it (13). |
| **F. Dependent tasks** | When a workflow task succeeds, its outputs are stored and each child's dependency counter goes down by one, in the same transaction (14). A child whose counter reaches zero is queued with its own outbox row (15). The executor fills upstream outputs into the child's request (16). |

## 3. Scope

**In scope**
- Single jobs and DAG workflows of HTTP calls to internal services, with data passed between tasks.
- At-least-once execution, no concurrent or repeated execution, retries with backoff, a dead-letter queue with redrive.
- Automatic protection: an endpoint that keeps failing is suspended; a recurring job that dead-letters 3 times in a row is paused.
- Teams as the isolation boundary, email and password accounts, team API keys.
- 20K executions/s sustained and 50K/s at peak.

**Out of scope (v1)**
- Running scripts, containers or arbitrary code; calling the public internet.
- Asynchronous callback jobs (a `202` followed by a later completion report).
- Roles inside a team, membership in several teams, an administration API.
- Email verification, self-service password reset, single sign-on and multi-factor authentication.
- A web console, multi-region active-active deployment, dynamic fan-out in workflows.

## 4. Requirements

| Area | Requirement |
|---|---|
| Throughput | 20K executions/s sustained; 50K/s at peak |
| Start precision | p99 of (actual start − scheduled time) ≤ 2s |
| Delivery | At least once; never concurrently; never again after completion |
| Durability | No accepted job or due run is lost (RPO = 0 for committed writes) |
| Availability | No single point of failure; losing a node delays work by at most about 20s; losing one database cell affects only its share of runs |
| Isolation | A team can never see or change another team's data, and cannot exhaust shared capacity |
| Retention | 7 days for run history, task outputs, resolved DLQ entries and deleted jobs; open DLQ entries are kept until resolved |

## 5. Architecture

| Service | Responsibility | Scales with |
|---|---|---|
| **kairos-api** | REST API: accounts, teams, API keys, secrets, endpoints, jobs, workflows, runs, DLQ, stats | Request rate |
| **kairos-watcher** | Owns shards through leases; fires due schedules and due retries; runs the outbox relay; recovers runs whose executor died; auto-suspend and other periodic checks | Number of shards |
| **kairos-executor** | Consumes dispatch topics; Redis lock and fenced claim; renders and sends HTTPS requests; classifies outcomes; publishes results | Kafka lag, in-flight calls |
| **kairos-state** | Consumes results; settles them in one transaction per batch and cell: history, retries, dead letters, workflow progress, follow-up outbox rows | Result throughput |
| `kairos-migrations`, `kairos-maintenance` | Database migrations before a release; daily partition management and purges, plus operator commands | — |

| Store | Role |
|---|---|
| **PostgreSQL** | Source of truth. A **control database** (teams, users, keys, endpoints, secrets) and two **cell databases** (jobs, schedules, runs, outbox, history, DLQ) |
| **Kafka** | Transport: dispatch topics per priority tier, results, cancel broadcasts, events. Every message can be rebuilt from PostgreSQL. |
| **Redis** | Run execution locks, "request sent" markers, API rate limits. No business data. |

**Principles:** the database decides and Kafka delivers; messages leave only through the outbox; unfinished runs live in a small hot table and finished attempts in append-only history; every hot-path statement is set-based; retries are durable timers in the database, never sleeping threads.

## 6. Execution guarantees

**At least once.** Every message caused by a state change (dispatches, retries that become due, workflow steps, cancels, events) is written to the `outbox` table in the same transaction as the change. The relay publishes rows and deletes them only after Kafka acknowledges. A crash before the delete republishes the row, and nothing pending lives only in memory.

**No duplicate execution: four gates before every call.**

| Gate | Mechanism | Stops |
|---|---|---|
| 1. Redis run lock | `kairos:lock:run:{runId}`, taken atomically; TTL = HTTP timeout + 10s | Two executors running the same run, including a message delivered twice |
| 2. Fenced claim | `QUEUED → RUNNING` only if the message's fence matches the row | Stale or duplicate messages, cancelled runs, and lost Redis locks after a failover |
| 3. Send-time guard | The lock must still have at least timeout + 2s left just before sending | An executor paused (GC, CPU starvation) past its lock |
| 4. Sent marker | Written just before the request leaves | Tells recovery whether the target may have received the request |

**Unknown outcomes.** If an executor dies, the watcher's lease sweeper checks the run 30s after its lease ends. Without a sent marker the run is safely re-queued. With a marker, the outcome is unknown, and the endpoint's `unknownOutcomePolicy` decides: `RETRY_WITH_IDEMPOTENCY_KEY` (default) or `REVIEW` (dead-letter for a person to decide). Timeouts and connections dropped after sending are treated the same way.

If Redis is down, executors stop calling targets (fail closed); work waits in Kafka and the outbox.

## 7. Teams and access

- **Accounts.** Email and password only. Passwords are hashed with Argon2id. Login failures return a generic error, are rate-limited per IP and per account, and lock the account for a growing period. Login returns a 15-minute ES256 access token and a 30-day refresh token that rotates on every use; reusing an old refresh token revokes the whole session.
- **Teams.** A user belongs to at most one team. A user without a team can only create one or wait to be added by a member. All members are equal: any member can manage endpoints, secrets, jobs, workflows and API keys, triage the DLQ, add or remove members, and pause, cancel or delete any job of the team.
- **Isolation.** The team id comes only from the verified token or API key, and every query filters on it. Another team's ids return `404`.
- **Which hosts jobs may call.** Any internal API, without approval. A platform-wide policy allows HTTPS only, to internal DNS suffixes or internal address ranges on allowed ports, and always refuses loopback, metadata, Kubernetes API and Kairos infrastructure addresses. The executor checks every resolved address and connects to that checked address.
- **Quotas.** Each team gets default quotas (jobs, executions per second, in-flight calls, workflow size, backfill and redrive rates). Over-quota API calls get `429`; over-quota runs are deferred, never dropped. Operators change quotas with `kairos-maintenance`.

## 8. Partitioning: shards and cells

Each execution writes about 7 rows: advance the schedule, insert the run, insert and later delete the outbox row, claim, delete the live run, insert history. At 50K/s that is about **350K row writes/s**, too much for one PostgreSQL primary, so the hot data is split:

- **Shard:** every job and workflow is hashed into one of 256 fixed buckets, `murmur3(team_id + name) mod 256`. All its runs, outbox rows and history live in that shard, and job and run ids embed the shard number, so any id routes to its database without a lookup.
- **Cell:** a PostgreSQL database (primary, HA standby, read replica) holding a range of shards. Production starts with 2 cells (shards 0–127 and 128–255), each taking about 175K row writes/s at peak. If an early per-cell load test shows that is too much, the layout becomes 4 cells of 64 shards with no code change.
- Watchers split the 256 shards between themselves with 15-second leases, so two watchers never poll the same rows. Every hot-path transaction touches exactly one cell; only team-wide lists read all cells.

**Why PostgreSQL and not Cassandra.** The write volume alone does not require leaving a relational database; the guarantees decide it.

| Need | PostgreSQL cells | Cassandra |
|---|---|---|
| Outbox row atomic with the state change | One local transaction | No multi-partition transactions |
| Fenced claim and unique occurrences | A conditional update and a unique index, set-based | A lightweight transaction (Paxos) per run |
| Atomic settlement (history, retry or DLQ, workflow counters, outbox) | One transaction | Separate writes, each with its own repair path |
| Rows inserted and deleted within seconds | Partitioning and vacuum tuning | Tombstone build-up, a known anti-pattern for queues |

Systems with the same needs run on sharded relational stores (Meta FOQS on sharded MySQL, Slack's cron on Vitess, Pinterest PinLater and Segment Centrifuge on MySQL). If running cells ever becomes the bottleneck, the fallback is distributed SQL (Cloud Spanner or CockroachDB), which keeps transactions at the cost of higher commit latency and price.

## 9. Capacity

| Resource | 20K/s sustained | 50K/s peak | How it scales |
|---|---|---|---|
| PostgreSQL row writes | ~140K/s | ~350K/s | 2 cells (4 if needed) |
| Kafka | ~32 MB/s | ~80 MB/s | Partitions sized for peak: 24 / 48 / 24 dispatch, 48 result |
| Redis | ~60K ops/s | ~150K ops/s | Memorystore with a replica |
| Executors | ~2K calls in flight at 100 ms latency | ~5K | Async I/O, autoscaled on Kafka lag |
| History | ~0.7 TB/day at sustained rate | — | Daily partitions, dropped after 7 days |

Above the provisioned rate nothing is dropped: work waits in the outbox and Kafka, high-priority runs keep half the executor capacity, services autoscale, and a lag alert fires.

## 10. Failure handling

| Failure | Recovery |
|---|---|
| Watcher dies | Its shard leases expire in 15s; other watchers take over and publish its outbox rows |
| Executor dies mid-call | Lease sweeper re-queues it, or applies the unknown-outcome policy if the request may have been sent |
| Duplicate or late message | Rejected by the Redis lock and the fence |
| Kafka down | Outbox rows accumulate; watchers stop claiming new work once the oldest due row is 10s old |
| Redis down | Executors pause; nothing is lost |
| A cell fails over | Only that cell's shards pause |
| Target down | Circuit breaker defers runs; sustained failure suspends the endpoint |
| Rolling deploy | Executors drain for up to 60s; unsent calls are re-queued |

## 11. Security

- Argon2id passwords; hashed refresh tokens and API keys (HMAC-SHA256 with a pepper, constant-time comparison); short-lived signed access tokens.
- Team isolation on every query; strict input validation with unknown fields rejected; parameterised SQL only; generic error responses.
- HTTPS-only calls to internal targets with certificate verification, a fixed deny list and DNS-rebinding protection; redirects off by default.
- Target credentials encrypted with per-team keys sealed by Cloud KMS; never in Kafka, logs or responses. Requests are signed (`X-Kairos-Signature`) so targets can verify the caller.
- Infrastructure secrets from Secret Manager; per-service database, Kafka and Redis users; non-root, read-only containers; audit log for security-relevant actions.

## 12. Observability

- **Primary SLO:** schedule lag (start time − due time), p99 ≤ 2s.
- **Metrics:** fires, outbox backlog and age, Kafka lag, claims, calls by outcome, retries, DLQ size and age, suspended endpoints, login failures, database health.
- **Alerts:** lag p99 > 2s, outbox backlog, Redis down, endpoint suspended, job failing most of its recent runs, DLQ growing, executor saturation, login-failure spike.
- OpenTelemetry traces flow through Kafka to targets via `traceparent`; logs are JSON without payloads or secrets.

## 13. Deployment and tech stack

| Concern | Choice |
|---|---|
| Runtime | GKE (regional, private), one Deployment per service; KEDA autoscaling on Kafka lag |
| Databases | Cloud SQL for PostgreSQL: 1 control + 2 cell instances, HA with read replicas, behind PgBouncer |
| Messaging, cache | Managed Service for Apache Kafka; Memorystore for Redis (TLS) |
| Secrets | Secret Manager and Cloud KMS |
| Code | Java 25, Spring Boot 4, Gradle monorepo (`services/*`, `libs/*`, `jobs/*`, `tools/*`), Reactor Netty HTTP client, Protobuf, Resilience4j, Tink |
| Local | Docker Compose with all four services, PostgreSQL (control + 2 cells), Kafka, Redis, Prometheus, Grafana and a mock target |
| Testing | Unit and property tests, Testcontainers integration tests, team-isolation API tests, and k6 / bench-cli load scenarios: 20K/s steady state, ramp to 50K/s, retries and DLQ, workflows, chaos, duplicate-execution proof |

## 14. Key decisions

| Decision | Rationale |
|---|---|
| Four microservices in one monorepo | Independent scaling and deploys, one build and version |
| PostgreSQL cells as source of truth, Kafka as transport | Transactions and constraints for the guarantees; Kafka for fan-out and backpressure |
| Transactional outbox for every state-driven message | At-least-once without dual writes |
| Redis lock + fenced database claim | Cheap mutual exclusion; the fence keeps it safe across Redis failovers |
| Retries as durable `DELAYED` runs | Survive restarts, are queryable, never block Kafka partitions |
| Small live table + append-only history | No bloat on a table with over a billion rows a day; retention by dropping partitions |
| One team per user, no roles, team from the token | Isolation is the requirement; a single authorization check |
| Platform-wide target policy, no approvals | Any internal API can be scheduled immediately, infrastructure stays protected |
| Placeholder templates, no expression language | No code-injection surface in workflow data passing |

## 15. Risks

| Risk | Mitigation |
|---|---|
| A cell cannot hold its share of peak writes | Per-cell load test before anything else is built; move to 4 cells |
| Outbox churn of 50K inserts and deletes per second | Hash-partitioned table, batched deletes, tuned autovacuum |
| Redis on the execution path | Replica with failover, fail-closed executors, fence as the backstop |
| One team starves others | Quotas, per-endpoint limits, priority tiers |
| A job targets a sensitive internal service | Deny list, NetworkPolicies, request signing, audit of endpoint changes |
