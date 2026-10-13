# Kairos: Low-Level Design

This document describes how Kairos is built. The index below covers the design decisions, toolchain, repository layout and domain model. Each part file covers one area in detail.

| Part | Content |
|---|---|
| [01 Database](lld/01-database.md) | DDL for the control and cell databases (including accounts and organizations), code tables, partitioning, roles and grants, PostgreSQL settings, migrations and maintenance |
| [02 Data access](lld/02-data-access.md) | Every hot-path SQL statement and its transaction boundaries: fire, outbox relay, claim, settle, recovery, cancel, redrive, leases, sessions |
| [03 Messaging](lld/03-messaging.md) | Protobuf schemas, topic configuration, producer and consumer settings, the executor offset tracker, the outbox relay |
| [04 Services](lld/04-components.md) | Internals of `kairos-watcher`, `kairos-executor`, `kairos-state` and `kairos-api`: algorithms, threading, the Redis lock, shutdown |
| [05 API and security](lld/05-api-and-security.md) | Accounts and sessions, organizations and roles, API keys, REST conventions and DTOs, rate limiting, egress rules and the egress guard, cryptography, template and JSONPath grammars |
| [06 Observability and configuration](lld/06-observability-and-config.md) | Metrics, alert rules, tracing, logging, the configuration catalogue |
| [07 Platform](lld/07-platform.md) | Docker Compose, `kind`, the Helm chart, Terraform for GCP, CI |
| [08 Testing and benchmarks](lld/08-testing-and-benchmark.md) | Test strategy per module, the mock target, `bench-cli`, scenarios, the report format |

Diagram sources live in [`lld/diagrams/src`](lld/diagrams/src); the rendered SVGs sit next to them.

---

## 1. Design decisions

The HLD (§21) records the architectural alternatives considered. These are the decisions that shape the implementation.

| # | Decision | Reason |
|---|---|---|
| D1 | A **monorepo** with four service modules (`kairos-api`, `kairos-watcher`, `kairos-executor`, `kairos-state`), each built into its own image, plus shared library modules | Independent deploys and scaling with one build and one version |
| D2 | Shared orchestration used by more than one service lives in the `kairos-engine` library: `SettlementService`, `OutboxWriter`, `CellRouter`, catalogs | `kairos-state` and the watcher's lease sweeper share one settlement path |
| D3 | **Built-in accounts:** Argon2id password hashes; 15-minute ES256 access tokens; 30-day opaque refresh tokens stored hashed and rotated on every use, with reuse detection | Self-contained identity with short-lived credentials and theft detection |
| D4 | Every organization-owned resource lives under `/v1/orgs/{orgId}`. Authorization combines the caller's membership role (users) or the key's organization and scopes (API keys). | Users belong to several organizations, and explicit paths make checks and audits unambiguous |
| D5 | Egress rules are rows with a lifecycle: `PENDING_VERIFICATION`, `PENDING_APPROVAL`, `ACTIVE`, `REJECTED`, `REVOKED`. Host rules are verified with a well-known token; suffix and CIDR rules need platform approval. | Self-registered teams must not be able to target arbitrary internal services |
| D6 | **Targets must use HTTPS** (TLS 1.2 or later, chain and hostname verified). Internal CAs come from per-organization CA bundles. | Verified TLS on every outbound call |
| D7 | `API_KEY` authentication to targets sends the key in a header only, never in a query string | Keys in URLs leak into access logs |
| D8 | Secrets and sensitive outputs are sealed with **Tink XChaCha20-Poly1305** using per-organization keysets encrypted by a Cloud KMS key. Token-signing keys use a platform keyset sealed the same way. | No KMS call per message, and no per-key message limit |
| D9 | One internal state, `DELAYED`, covers retries, deferrals, paced backfill and bulk redrive | A single durable-timer mechanism |
| D10 | Aggregates are kept per organization and per endpoint only. Per-job statistics are read from `run_history`. | A per-job, per-minute aggregate would add thousands of writes per second |
| D11 | Per-job health lives in a sparse `job_health` table that is written only after a recent failure | No extra writes on the healthy path |
| D12 | The `outbox` table, with `available_at`, is the durable dispatch timer. The relay polls it about every 100 ms. | No pending work held only in memory |
| D13 | Jobs resolve the endpoint's current version from a cache refreshed every 5s | No join to the control database on the hot path |
| D14 | API and login rate limiting use **Bucket4j on Redis** and fail closed | A distributed limiter without load on PostgreSQL |
| D15 | The executor uses a plain `KafkaConsumer` with its own offset tracker. The state processor uses Spring Kafka batch listeners. | Exact control over pause, resume and commits where it matters |
| D16 | Kafka payloads are **Protobuf**, and `buf` checks for breaking changes | Compact, fast, with safe evolution |
| D17 | Data access uses Spring `JdbcClient` with hand-written, parameterised SQL | Set-based SQL stays explicit and reviewable |
| D18 | The OpenAPI spec is generated from code, and a committed snapshot is compared in CI | Contract changes are caught |
| D19 | The HTTP client is **Reactor Netty** with an address resolver that validates every resolved IP | Defeats DNS rebinding, while TLS still verifies the real host name |
| D20 | The URL host is never templated | Upstream data cannot redirect a call to another host |
| D21 | Every state-driven Kafka message is an `outbox` row in the same transaction, published only by `OutboxRelay` | At-least-once delivery (HLD §9.1) |
| D22 | A **Redis run lock** with owner token, fence-aware takeover and compare-and-delete release, plus the send-time guard and sent markers | No duplicate execution (HLD §9.2) |
| D23 | An `unknownOutcomePolicy` per endpoint (`RETRY_WITH_IDEMPOTENCY_KEY` by default, or `REVIEW`). The classifier separates failures before sending from failures after sending. | The one case where at-least-once and no-duplicate conflict is decided explicitly |
| D24 | `RedisFailoverMonitor` watches `master_replid`. After a failover, missing sent markers count as unknown for 310s. | A failover can lose the newest markers |
| D25 | **Cells:** one control database plus N cell databases, with 2 in the reference production layout. Flyway has two locations (`db/control`, `db/cell`), and `CellRouter` maps a shard to a cell `DataSource`. | About 350K row writes/s at the 50K/s peak |
| D26 | Run, workflow-run, DLQ and outbox ids are UUIDv7 values with the shard in their low 8 bits | Any id routes to its cell without a lookup |
| D27 | Executor claims and state settles are grouped by cell: one statement or transaction per cell per batch, run in parallel. A Kafka batch is acknowledged only after every cell has committed. | No cross-cell transactions |
| D28 | Organization-wide queries fan out to the cells' read replicas in parallel and merge the results. The cursor carries a position per cell. | Lists span cells; single-item reads go to one cell |
| D29 | Partition creation and removal run in `kairos-maintenance` (a CronJob) with a DDL-capable database user | Services hold DML privileges only |
| D30 | **Java 25** | Current LTS; virtual threads no longer pin on `synchronized` |

---

## 2. Toolchain and versions

Versions are pinned in `gradle/libs.versions.toml`. Every dependency is locked (`gradle.lockfile`) and checksum-verified (`gradle/verification-metadata.xml`). Container images are pinned by digest.

| Component | Version | Notes |
|---|---|---|
| Java | 25 LTS (Eclipse Temurin) | Gradle toolchain |
| Gradle | 9.8.1 (wrapper) | |
| Spring Boot | 4.1.1 | The BOM manages Spring Framework, Spring Security, Spring Kafka, Micrometer, kafka-clients, the PostgreSQL driver, HikariCP, Flyway, Reactor Netty, Netty, Testcontainers and Jackson |
| Apache Kafka | Broker 4.x (KRaft) | |
| PostgreSQL | 18 | Native `uuidv7()`, asynchronous I/O |
| Redis | 8.x locally; Memorystore for Redis on GCP | Locks, sent markers, rate limits |
| Protobuf | protobuf-java 4.36.2 | Kafka payloads |
| Google Tink | 1.23.0; tink-gcpkms 1.12.0 | AEAD, MAC, signatures, keyset encryption with KMS |
| Nimbus JOSE + JWT | Latest stable | Access-token signing and JWKS |
| Bouncy Castle | Latest stable | Argon2id |
| Resilience4j | 2.4.0 | Breakers, bulkheads, rate limiters |
| Bucket4j | 8.21.0 (`bucket4j_jdk17-core`, `bucket4j_jdk17-lettuce`) | Rate limiting on Redis |
| Lettuce | Spring Boot BOM | Asynchronous, pipelined Redis client |
| cron-utils | 9.2.1 | Unix cron parsing |
| Caffeine | Spring Boot BOM | In-process caches |
| springdoc-openapi | 3.1.1 | OpenAPI generation |
| picocli | 4.7.7 | `bench-cli` |
| Testcontainers | Spring Boot BOM | PostgreSQL, Kafka and Redis integration tests |
| jqwik / ArchUnit / JMH | 1.10.2 / 1.5.1 / 1.37 | Property tests, architecture rules, micro-benchmarks |
| k6 | 0.56 | API load tests |
| `kind`, Helm, Terraform, tflint, `buf` | Latest stable | Local Kubernetes, packaging, infrastructure, schema checks |

---

## 3. Repository layout

```
kairos/
├── settings.gradle.kts   build.gradle.kts   gradle/libs.versions.toml   gradle/verification-metadata.xml
├── build-logic/                 convention plugins: java-library, spring-service, proto, test-conventions
├── services/
│   ├── kairos-api/              accounts, organizations, scheduling REST API, platform administration
│   ├── kairos-watcher/          shard leases, fire and delayed pollers, outbox relay, sweepers, singleton tasks
│   ├── kairos-executor/         tier consumers, run lock, claims, HTTP pipeline, cancel listener
│   └── kairos-state/            result listener, settlement coordinator, stats aggregation
├── jobs/
│   ├── kairos-migrations/       Flyway runner for db/control and db/cell
│   └── kairos-maintenance/      partition management CronJob
├── libs/
│   ├── kairos-domain/           pure Java: model, state machine, policies, planners, cron, DAG, templates, egress rules, permissions
│   ├── kairos-proto/            .proto files and generated code
│   ├── kairos-persistence/      JdbcClient repositories, SQL, Flyway migrations
│   ├── kairos-messaging/        Kafka producers and consumers, codecs, offset tracker
│   ├── kairos-security/         password hashing, JWT, API keys, Tink crypto, egress resolver
│   ├── kairos-http/             outbound client registry, auth providers, endpoint limiters, response reader
│   └── kairos-engine/           shared orchestration: SettlementService, OutboxWriter, CellRouter, catalogs, node registry
├── tools/
│   ├── mock-target/             Netty HTTPS benchmark target
│   └── bench-cli/               picocli benchmark tool
├── deploy/
│   ├── compose/                 docker-compose.yml, configuration, Grafana provisioning
│   ├── kind/                    cluster definition and operator values
│   ├── helm/kairos/             Helm chart: one Deployment per service, migration Job, maintenance CronJob
│   └── terraform/               modules and the GCP reference environment
├── bench/                       scenarios, k6 scripts, reports (ignored by git)
└── scripts/                     dev-keys.sh, bench.sh, kind-up.sh, verify-iac.sh
```

![Module dependencies](lld/diagrams/lld-modules.svg)

**Dependency rules** (enforced by Gradle and ArchUnit):
- `kairos-domain` depends only on the JDK and cron-utils.
- Libraries never depend on services, and services never depend on each other.
- `kairos-executor` does not depend on the account or organization code. Each service image contains only what that service needs.
- Controllers in `kairos-api` call application services, never repositories.
- Every repository method that reads organization-owned data takes a `TenantId` as its first parameter.

| Service | Depends on |
|---|---|
| `kairos-api` | domain, persistence, messaging, security, engine |
| `kairos-watcher` | domain, persistence, messaging, engine, security (Redis client for the lease sweeper) |
| `kairos-executor` | domain, persistence, messaging, security, http, engine |
| `kairos-state` | domain, persistence, messaging, engine |

---

## 4. Domain model (`kairos-domain`)

Package root: `dev.kairos.domain`.

| Package | Key types | Responsibility |
|---|---|---|
| `common` | `TenantId`, `UserId`, `JobId`, `WorkflowId`, `WorkflowRunId`, `RunId`, `EndpointId`, `ShardId`, `Fence`, `Attempt`; `Uuid7`; `Shards`; `TimeSource` | Typed identifiers, shard computation and embedding, one clock abstraction |
| `identity` | `User`, `PasswordPolicy`, `Membership`, `Role`, `Permission`, `PermissionMatrix`, `Invitation`, `ApiKeyScopes` | Roles map to permissions; a key's scopes are checked against its creator's permissions |
| `egress` | `EgressRule`, `EgressRuleStatus`, `EgressPolicy`, `HostRule`, `Cidr`, `IpNormalizer`, `EgressDecision`, `GlobalDenyList` | The rule lifecycle and pure matching logic |
| `schedule` | `ScheduleSpec` (sealed), `NextFireCalculator`, `DstPolicy`, `MisfirePolicy`, `MisfirePlanner`, `SpreadCalculator` | Next fire times in local time; misfire plans |
| `run` | `RunState`, `Outcome`, `ErrorClass`, `Classification`, `Trigger`, `InflightRun`, `AttemptResult`, `RunTransitions` | The run state machine as an explicit transition table |
| `retry` | `RetryPolicy`, `BackoffStrategy` (sealed), `RetryPolicyResolver` | Policy resolution: job, endpoint, organization, system |
| `endpoint` | `EndpointSpec`, `AuthSpec` (sealed), `UrlTemplate`, `SuccessRule`, `StatusOverrides`, `Limits`, `OutputSpec`, `SuspendPolicy`, `UnknownOutcomePolicy` | Immutable value objects validated on construction |
| `classify` | `OutcomeClassifier` | Status, exception, send phase and overrides → `Classification` |
| `template` | `TemplateParser`, `Renderer`, `RenderContext`, `JsonPathLite` | Safe placeholders and a JSONPath subset |
| `dag` | `WorkflowSpec`, `TaskSpec`, `DagValidator`, `DagPlan` | Kahn's algorithm, depth limit, ancestor-only references |
| `plan` | `FirePlanner`, `SettlementPlanner`, `SettlementPlan`, `DagTransitionPlanner`, `Decision` | Pure functions that turn inputs into database operations and outbox rows |
| `health` | `JobHealth`, `AutoPauseRule`, `EndpointHealthWindow`, `AutoSuspendRule` | Protection decisions |
| `dlq` | `DeadLetter`, `TriageStatus`, `TriageTransitions`, `RedriveMode` | The triage state machine |
| `quota` | `Quotas`, `QuotaCheck` | Per-organization limits |

![Settlement planning](lld/diagrams/lld-settlement.svg)

`SettlementPlanner` is the single place that decides what happens to a run. It is used by `kairos-state`, by the watcher's lease sweeper, by cancellation and by the workflow review timeout. Because it is pure, it is unit-tested without a database.

---

## 5. Services

| Service | Main classes |
|---|---|
| `kairos-api` | **Accounts:** `AuthController`, `MeController`, `SignupService`, `LoginService`, `SessionService`, `PasswordResetService`, `EmailVerificationService`, `JwtIssuer`, `JwksController`. **Organizations:** `OrgController`, `MemberController`, `InvitationController`, `ApiKeyController`, `EgressRuleController`, `SecretController`, `AuditController`. **Scheduling:** `EndpointController`, `JobController`, `WorkflowController`, `WorkflowRunController`, `RunController`, `DlqController`, `StatsController`. **Administration:** `AdminOrgController`, `AdminEgressController`, `AdminUserController`. **Cross-cutting:** `BearerAuthenticationFilter` (JWT or API key), `OrgAccessEvaluator`, `RateLimitFilter`, `IdempotencyService`, `ProblemDetailsAdvice`, `NotificationPort` (email) |
| `kairos-watcher` | `ShardLeaseManager`, `FirePoller`, `DelayedPoller`, `OutboxRelay`, `StuckQueuedSweeper`, `LeaseSweeper`, `RedisFailoverMonitor`, `WakeupListener`, `EgressVerifier` (well-known token checks), `SingletonTasks` |
| `kairos-executor` | `TierConsumerRunner` (one per tier), `OffsetTracker`, `RunLockService`, `ClaimService`, `SendGuard`, `RunPipeline`, `InflightRegistry`, `CancelListener`, `ExecutorLifecycle` |
| `kairos-state` | `ResultBatchListener`, `SettleCoordinator`, `StatsAggregator` |
| `kairos-engine` (library) | `CellRouter`, `SettlementService`, `OutboxWriter`, `EndpointCatalog`, `WorkflowCatalog`, `EgressRuleCatalog`, `PolicyLookup`, `NodeIdentity`, `NodeRegistryHeartbeat` |

| Pattern | Where it is used |
|---|---|
| Transactional outbox | `outbox` table, `OutboxWriter` (same transaction), `OutboxRelay` (publish, then delete) |
| Distributed lock with fencing | `RunLockService`, fenced claim, `SendGuard` |
| Competing consumers, idempotent consumer | `TierConsumerRunner` with fenced claims; `ResultBatchListener` with fenced settles |
| Lease with fencing token | `ShardLeaseManager`, claim and settle SQL |
| State machine | `RunTransitions`, `TriageTransitions`, `EgressRuleStatus` |
| Strategy | `BackoffStrategy`, `AuthHeaderProvider`, `MisfirePolicy`, `DstPolicy` |
| Functional core, imperative shell | `FirePlanner`, `SettlementPlanner` → `SettlementService` |
| Circuit breaker, bulkhead, rate limiter | `EndpointLimiterRegistry` (Resilience4j), Bucket4j |
| Registry | `EndpointCatalog`, `TargetHttpClientRegistry` |
| Template method | `AbstractShardPoller` |
| Pipes and filters | `RunPipeline` stages |
| Ports and adapters | `NotificationPort`, `KmsPort`, repositories |
| CQRS-lite | `kairos-api` reads from replicas; writes go to primaries |

---

## 6. Implementation plan

| Milestone | Deliverables | Done when |
|---|---|---|
| **M1 Skeleton** | Gradle wrapper, `build-logic`, version catalog, locking; all library and service modules; Flyway control and cell schemas; `CellRouter` with two local cells; Compose stack; health and metrics endpoints; `scripts/dev-keys.sh`; basic mock target | `./gradlew build` passes; `docker compose up` is healthy; migrations apply |
| **M2 Throughput spike** | Watcher (leases, fire poller, outbox and relay, lease sweeper); executor (tier consumers, Redis lock, claim, send guard, sent marker, HTTPS call, classification, result); state (success, retry, DLQ); `bench-cli` for S1; benchmark dashboard | S1 passes locally with 0 lost runs; S9 shows 0 concurrent duplicates; the per-cell write ceiling is recorded |
| **M3 Accounts and organizations** | Signup, email verification, login, refresh rotation, password reset; organizations, roles, invitations, API keys; egress rules with verification and approval; secrets; quotas; platform administration; audit log | The identity and authorization test suites pass; S12 (onboarding) passes |
| **M4 Scheduling features** | Endpoint registry and auth providers; full classification and retry policies; DLQ triage and bulk redrive; auto-suspend and auto-pause; cancellation; misfire and backfill; priority tiers; request signing; metrics and alerts | S3, S4 and S7 pass; the OpenAPI snapshot is committed |
| **M5 Workflows** | DAG validation, renderer, JSONPath subset, output extraction and sealing, orchestration, block-for-review, workflow-run retry | S6 passes, including the scan for sensitive values |
| **M6 Platform** | Helm chart, `kind` environment, chaos tooling, Terraform modules, infrastructure checks, runbooks, benchmark report | Chart and Terraform checks are clean; S5 and S11 pass |
