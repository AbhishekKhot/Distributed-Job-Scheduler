# Kairos: Implementation Checklist

This checklist breaks the build of Kairos into tasks that can be picked up independently and worked on in parallel. Each task names the module and packages it owns, the tasks it depends on, its sub-tasks and its acceptance criteria. [HLD.md](HLD.md) and [LLD.md](LLD.md) are the source of truth for behaviour, tables and API contracts. Where this checklist and the LLD disagree, the LLD wins, and the checklist is corrected in the same pull request.

## Contents

1. How to work with this checklist
2. Working environment (Docker only)
3. Definition of done
4. Engineering standards
5. Task overview and parallel waves
6. Phase 0: repository and tooling
7. Phase 1: foundations (libraries)
8. Phase 2: services
9. Phase 3: verification and benchmarks
10. Phase 4: deployment

---

## 1. How to work with this checklist

**Statuses.** Each task header carries a status line:

| Marker | Meaning |
|---|---|
| `todo` | Not started; free to claim once its dependencies are `done` |
| `in progress (owner)` | Claimed; nobody else works on it |
| `review` | Pull request open |
| `done` | Merged to `develop`, acceptance criteria met |

Sub-tasks are GitHub task-list items (`- [ ]`). They are ticked in the same pull request that completes them.

**Claiming a task.**
1. Pick a `todo` task whose "Needs" are all `done`. A task may also start early against the interfaces of an unfinished dependency if that dependency has already merged its interfaces (noted as "interfaces first" in the task).
2. Set the status to `in progress (name)` in a one-line commit on `develop`, or in the first commit of the task branch.
3. Branch name: `t-<id>-<short-name>`, for example `t-205-jobs-api`. One task per branch and per pull request. Large tasks may be split into several pull requests, one per sub-task group.
4. Commit messages follow Conventional Commits with the module as the scope: `feat(api): add job creation`, `fix(engine): renew lease before expiry`, `test(executor): cover send-time guard`.

**Ownership rules (what keeps parallel work conflict-free).**
- A task changes only the paths listed under "Owns". Anything else it needs is requested from the owning task, or done in a small separate pull request that names the reason.
- Shared files have fixed rules:

| Shared file | Rule |
|---|---|
| `gradle/libs.versions.toml` | Add entries in alphabetical order only; never change another task's entry without agreement |
| `gradle.lockfile`, `gradle/verification-metadata.xml` | Never edited by hand. After a rebase that conflicts, take either side and run `make locks` |
| `settings.gradle.kts` | Only T-002 and T-003 add modules |
| Flyway scripts | Versioned by timestamp (`V2026_10_20_1530__add_x.sql`), so two tasks never pick the same number. Merged scripts are never edited |
| `deploy/compose/*` | Owned by T-001; other tasks add environment variables only, in the service blocks they own, plus `.env.example` and `scripts/dev-env.sh` for new secrets |
| `LLD.md` | Any contract change is made in the same pull request as the code and called out in its description |

**Feature slices.** Every service is organised by feature, so two tasks on the same service touch different packages (see section 4.1). For example, T-205 owns `dev.kairos.api.jobs`, and T-206 owns `dev.kairos.api.workflows`.

---

## 2. Working environment (Docker only)

Nothing runs on the host apart from Docker, `make`, `bash` and `openssl`. Gradle runs inside the pinned JDK 25 builder image, and the stack always runs detached in Docker Compose.

| Command | What it does |
|---|---|
| `make env` | Once: writes `deploy/compose/.env` with random credentials (mode 600, gitignored) |
| `make build` | Compile, unit tests and boot jars, in a throwaway container |
| `make test` | All checks, including Testcontainers integration tests (the Docker socket is mounted into the build container) |
| `make gradle ARGS="..."` | Any Gradle command, for example `ARGS=":libs:kairos-domain:test --tests '*Cron*'"` |
| `make bg ARGS="check"` | The same Gradle command in a detached container named `kairos-gradle` |
| `make bg-logs` / `make bg-wait` | Follow the detached run / wait for it and exit with its status |
| `make locks` | Refresh lockfiles and checksums after adding or upgrading a dependency |
| `make images` | Build every application image from `deploy/docker/Dockerfile` |
| `make up` / `make up-all` | Build and start the stack detached (`--detach --wait`); `up-all` adds Prometheus and Grafana |
| `make ps`, `make logs S=kairos-executor` | Status and logs of the detached stack |
| `make down` / `make clean` | Stop the stack / also delete its volumes and the Gradle cache |

Local endpoints: API `http://127.0.0.1:8080` (Swagger UI at `/swagger-ui.html`), Prometheus `127.0.0.1:9090`, and Grafana `127.0.0.1:3000` (`admin`, password in `.env`). Infrastructure ports are not published. Use `docker compose exec postgres psql -U postgres -d kairos_cell0` and similar.

**Adding a dependency:** add it to `gradle/libs.versions.toml` with an exact version, reference it through the catalog, run `make locks`, and commit the catalog, lockfiles and `gradle/verification-metadata.xml` together. Only use artefacts from Maven Central or the Gradle Plugin Portal.

---

## 3. Definition of done

A task is `done` only when every item below holds.

- [ ] `make build` and `make test` pass; the compiler runs with `-Werror`, so there are no warnings.
- [ ] `make up` brings every container to `healthy`, and the change works in the running stack where that applies.
- [ ] Unit tests cover the logic, and integration tests (Testcontainers) cover every SQL statement, Kafka flow and Redis script the task adds. Line coverage is at least 80% for `libs/*` and at least 70% for services (JaCoCo, enforced by T-002).
- [ ] The architecture tests (ArchUnit, T-002) pass: no forbidden dependencies between layers or modules.
- [ ] Every public type and public method in `libs/*` has Javadoc. Service classes have a class-level Javadoc stating their responsibility.
- [ ] Every REST endpoint carries springdoc annotations and matches its LLD contract exactly (path, headers, body, status codes, error codes and messages).
- [ ] The security checklist in section 4.8 holds for the change.
- [ ] New metrics, logs and traces follow section 4.9.
- [ ] Configuration is typed (`@ConfigurationProperties` with validation) and documented in the module's `application.yml` with defaults.
- [ ] No secrets, credentials or personal data appear in code, tests, fixtures, logs or committed configuration.
- [ ] The pull request description lists the acceptance criteria and how each was verified.

---

## 4. Engineering standards

### 4.1 Architecture and module rules

Kairos follows ports and adapters. The domain is plain Java; frameworks sit at the edges.

| Module | May depend on | Must not depend on |
|---|---|---|
| `kairos-domain` | JDK, Guava (hashing only), cron-utils | Spring, JDBC, Kafka, Redis, Jackson, any other Kairos module |
| `kairos-proto` | protobuf-java | Everything else |
| `kairos-persistence`, `kairos-messaging`, `kairos-security`, `kairos-http` | `kairos-domain`, `kairos-proto`, their own infrastructure clients, Spring (no Spring Boot auto-configuration classes) | Services, each other unless listed in their build file |
| `kairos-engine` | All libraries above | Services |
| `services/*`, `jobs/*`, `tools/*` | Libraries | Other services, jobs or tools |

Package layout inside each service (feature slices):

```
dev.kairos.<service>
├── <feature>/                 one package per feature, for example jobs, endpoints, dlq
│   ├── web/                   controllers and request/response records (API only)
│   ├── application/           use cases and the ports they need
│   └── infrastructure/        adapters: SQL repositories, Kafka, Redis
├── config/                    Spring configuration and @ConfigurationProperties
└── support/                   cross-cutting helpers owned by the foundation task
```

Rules enforced by ArchUnit (T-002):
- `web` depends on `application`, never on `infrastructure`.
- `application` depends on domain types and its own ports, never on Spring web, JDBC or Kafka types.
- `infrastructure` implements ports. It is the only layer that touches `JdbcClient`, `KafkaTemplate` or Lettuce.
- No class in `kairos-domain` imports `org.springframework..`, `jakarta..`, `java.sql..` or `com.fasterxml..`.
- No cycles between packages.

### 4.2 SOLID and design patterns

| Principle | How it shows up in Kairos |
|---|---|
| Single responsibility | One use case class per operation (`CreateJobUseCase`, `PauseJobUseCase`). Controllers only map HTTP to a use case and back. Repositories hold SQL only. |
| Open/closed | New schedule kinds, auth types or error classes are added as new strategy implementations, without editing existing ones. |
| Liskov substitution | Every `ScheduleCalculator`, `AuthApplier` and `RetryBackoff` honours the contract documented on its interface, and a shared contract test (an abstract JUnit class) runs against each implementation. |
| Interface segregation | Narrow ports: `RunClaimPort`, `OutboxWriter`, `RunLockPort`, `SentMarkerPort`, `EndpointLookup`. No "god" repository. |
| Dependency inversion | Use cases depend on ports. Adapters are wired by Spring configuration. Domain code never instantiates infrastructure. |

Patterns used deliberately (and where):

| Pattern | Where |
|---|---|
| Strategy | `ScheduleCalculator` (one-time, cron, interval), `RetryBackoff` (exponential jitter, full jitter, fixed), `AuthApplier` (NONE, BASIC, BEARER, API_KEY, OAUTH2_CLIENT_CREDENTIALS), `MisfireHandler` |
| Factory | `RunFactory` (creates runs for schedule, immediate, manual, backfill and redrive triggers), `KairosIdFactory` |
| State machine | `RunState` transitions with an explicit transition table; illegal transitions throw |
| Transactional outbox | `OutboxWriter` used inside the same transaction as every state change |
| Template method | `ShardedPoller` base class for the fire poller, delayed poller and sweepers (lease check, batching, metrics, back-pressure) |
| Chain of responsibility | The four execution gates (lock, fenced claim, send-time guard, sent marker) as ordered `ExecutionGate` steps |
| Command | Operator commands in `kairos-maintenance` (picocli) |
| Adapter | Repositories, Kafka publishers and consumers, Redis scripts, the HTTP client |
| Builder | Immutable value objects with many optional fields (endpoint spec, job spec), implemented as records with static builders |
| Circuit breaker, bulkhead | Per-endpoint Resilience4j breaker and concurrency limit in the executor |

### 4.3 Java and Spring conventions

- Java 25: records for value objects and DTOs; sealed interfaces for results (`ClaimResult = Claimed | Stale | Cancelled`); pattern matching `switch`; `var` only when the type is obvious.
- No Lombok. No field injection; constructor injection only, with `final` fields. Classes are `final` unless designed for extension.
- Immutability by default. Collections returned from public methods are unmodifiable.
- `Optional` only as a return type. Never `null` in public APIs of `libs/*`; annotate with `@Nullable` (JSpecify) where unavoidable.
- Virtual threads are on (`spring.threads.virtual.enabled=true`). Blocking I/O is fine; do not block inside `synchronized` sections that guard hot paths, and use `ReentrantLock` where a lock is needed.
- Persistence uses `JdbcClient` with named parameters and set-based statements. No JPA or Hibernate: the hot paths need explicit SQL, batching and `FOR UPDATE SKIP LOCKED`.
- Time comes from an injected `java.time.Clock`, never `Instant.now()` directly, so tests control time.
- Configuration: prefix `kairos.*`, environment variables `KAIROS_*`, bound to `@ConfigurationProperties` records with `@Validated` constraints.
- Library wiring: every library exposes its beans through explicit `@Configuration(proxyBeanMethods = false)` classes. There is no Spring Boot auto-configuration and no component scanning of `dev.kairos` libraries; each service `@Import`s exactly the configurations it uses. Existing ones: `PersistenceConfiguration` (persistence), `HttpConfiguration` (http), `KeyManagementConfiguration`, `PlatformKeysetConfiguration`, `PasswordConfiguration`, `TokenConfiguration` and `ApiKeyConfiguration` (security), and `RunLockConfiguration`, `OutboxConfiguration` and `RunCreationConfiguration` (engine). A configuration imports the library configurations its beans need, uses the service's `Clock` and `MeterRegistry` beans when present, and binds its tunables to a validated `kairos.*` properties record.
- Errors: domain exceptions extend `KairosException` with a stable `ErrorCode`. Only the API layer maps them to HTTP.

### 4.4 Comments and Javadoc

- Javadoc on every public type and method in `libs/*`, and a class-level Javadoc on every Spring component in services. State what the type is responsible for, its invariants and its thread-safety.
- Inline comments explain why, not what: the reason for a lock order, a timeout value, a SQL hint or a deliberate deviation. Each one references the HLD or LLD section when it implements a documented rule ("LLD section 4.1: lock TTL = timeout + 10s").
- No commented-out code, no TODOs without a task id (`// TODO(T-305): ...`).
- `package-info.java` in every package describes the package's role.

### 4.5 REST API and Swagger

- Contracts come from LLD section 3. Paths, status codes, headers, error codes and messages match it exactly.
- Every controller class carries `@Tag(name, description)`. Every operation carries `@Operation(summary, description)`, `@ApiResponse` for each success and error status with `@Content(schema, examples)`, and `@Parameter` for path, query and header parameters.
- Common error responses (401, 403 `no-team`, 429, 500, 503) are added once by an `OperationCustomizer` in T-201, not repeated on every method.
- Request bodies are records validated with Jakarta Bean Validation (`@NotBlank`, `@Size`, `@Pattern` with simple anchored patterns, `@Min`/`@Max`). Unknown JSON fields are rejected (`spring.jackson.deserialization.fail-on-unknown-properties=true`).
- Errors are RFC 9457 problem details (`application/problem+json`) with `type`, `title`, `status`, `detail`, `instance`, `traceId` and, for validation, `errors[{field, code}]`. Messages never echo submitted values.
- `ETag` on single resources, `If-Match` required on `PUT` (412 on mismatch), `Location` on 201, `Idempotency-Key` on creates marked idempotent, `Retry-After` on 429 and 503.
- Cursor pagination: `limit` 1–200 (default 50) and an opaque `cursor` that is authenticated (HMAC) so it cannot be forged or edited.
- The OpenAPI document is generated at build time into `services/kairos-api/build/openapi/kairos-v1.json`, and a test fails if an LLD endpoint is missing from it (T-201).

### 4.6 Persistence

- Two kinds of database: control and cell (LLD section 2). Every cell-scoped call goes through `CellRouter`, which turns a shard (or an id with an embedded shard) into the right `DataSource`.
- One transaction never spans two databases. Cross-database steps are ordered so that a crash leaves a state that a sweeper or retry can finish (documented in each task).
- Parameterised SQL only (`JdbcClient` named parameters). No string concatenation of values. Dynamic `ORDER BY` or column choices use an allow-listed enum.
- Hot paths use batch statements, `FOR UPDATE SKIP LOCKED`, `INSERT ... ON CONFLICT DO NOTHING` and `RETURNING`. Every hot-path statement has an `EXPLAIN` check in its integration test (index used, no sequential scan on large tables).
- Schema changes go through Flyway in `jobs/kairos-migrations` only. Services connect as `sched_app` (DML only). Migrations connect as `sched_owner`.

### 4.7 Messaging

- Topics, keys, partitions and retention follow LLD section 4.2. Payloads are Protobuf from `kairos-proto`. Field numbers are never reused, and removed fields are `reserved`.
- Producers: `acks=all`, `enable.idempotence=true`, `compression.type=lz4`, and bounded `delivery.timeout.ms`.
- Consumers: manual offset commits after processing, the cooperative-sticky assignor, bounded `max.poll.records`, and pause/resume for back-pressure, never sleeping in the poll loop.
- Messages never contain secrets. The W3C `traceparent` travels in a Kafka header.
- A message that cannot be parsed is logged (without payload), counted (`kairos_messages_poison_total`) and skipped. It never blocks a partition.

### 4.8 Security checklist (applies to every task)

- [ ] All input is validated server-side: type, length, range and format, with allow-lists. Regular expressions are anchored and simple, and input length is capped before matching.
- [ ] Request bodies bind to explicit records; unknown fields are rejected; nothing is copied field by field from a map into an entity.
- [ ] Parameterised SQL only.
- [ ] The team id comes only from the verified token or API key. Every query on team data filters on it. Another team's ids return 404.
- [ ] Passwords use Argon2id. Refresh tokens are stored as SHA-256 hashes and API keys as HMAC-SHA256 with a pepper. All comparisons of secrets are constant-time (`MessageDigest.isEqual`).
- [ ] Random values that matter for security come from `SecureRandom` (tokens, key ids, secrets, nonces).
- [ ] Target credentials and sensitive outputs are encrypted with AES-GCM through Tink keysets, never logged, never put on Kafka, and never returned by the API.
- [ ] Outbound calls are HTTPS only, with hostname and chain verification, no automatic redirects, the platform target policy and the DNS-rebinding check (T-110).
- [ ] Error responses are generic. Details go to logs, with secrets and personal data redacted.
- [ ] Rate limits for login, signup and member/team creation use Redis and fail closed (429/503 when Redis is unavailable).
- [ ] Security headers are set on every response: `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` and `Content-Security-Policy: default-src 'none'` (Swagger UI gets its own relaxed policy). The API uses bearer tokens only and no cookies, so CSRF tokens do not apply. If a cookie is ever introduced, it must be `Secure; HttpOnly; SameSite=Strict` and protected by CSRF tokens.
- [ ] No secrets in code, tests or committed configuration. Local secrets come from `deploy/compose/.env` (generated), and production secrets from Secret Manager.
- [ ] Resources are closed deterministically (try-with-resources).
- [ ] No Java serialisation (`ObjectInputStream`), no `eval`-style template engines, and no shelling out.

### 4.9 Observability

- Metrics: Micrometer, names prefixed `kairos_`, base units (seconds, bytes), bounded label values. Never use team names, run ids or URLs as labels. Allowed labels include `service`, `cell`, `shard_group`, `topic`, `priority`, `outcome`, `error_class` and `endpoint_id`, the last only on endpoint-health metrics with a cardinality budget.
- Logs: structured ECS JSON (already configured), with `traceId`, `spanId`, `runId` and `teamId` in MDC where known. No request or response bodies, no headers carrying credentials, no tokens.
- Traces: OpenTelemetry through Micrometer Tracing. Spans for API requests, outbox publish, dispatch consume, the target call and settlement; `traceparent` is propagated to targets.
- Health: liveness stays simple. Readiness includes the control database, the cells the service needs, Kafka (where used) and Redis (executor and API).

### 4.10 Testing

| Level | Tooling | What it covers |
|---|---|---|
| Unit | JUnit 5, AssertJ | Domain rules and use cases with fake ports |
| Property | jqwik | Cron and DST iteration, backoff bounds, id/shard round trips, DAG validation |
| Integration | Testcontainers (Postgres 18.6, Kafka 4.3.1, Redis 8.8.3; the same digests as compose) | Every repository statement, Kafka flow and Redis script |
| Web slice | `@WebMvcTest` and the Spring MVC test client | Controllers: validation, status codes, problem bodies, OpenAPI annotations |
| Architecture | ArchUnit | Section 4.1 rules |
| End to end | bench-cli scenarios against `make up` | Full flows through the running stack (T-304) |
| Resilience | Scripted container kills and pauses | Guarantees under failure (T-305) |

Test names describe behaviour (`claimFailsWhenFenceIsStale`). Tests never sleep for timing; they use an injected `Clock`, Awaitility with short polls, or explicit latches.

---

## 5. Task overview and parallel waves

| Wave | Tasks that can run in parallel | Needs |
|---|---|---|
| 0 | T-001 (done), T-002 (done), T-003 (done) | none |
| 1 | T-101, T-105, T-106, T-109, T-110, T-112 | T-002, T-003 |
| 2 | T-102, T-103, T-104, T-107, T-108, T-111 | Wave 1 items listed per task |
| 3 | T-113, T-201, T-301 | T-107, T-108, T-109, T-111 |
| 4 | T-202, T-203a–d, T-204, T-221, T-224, T-241, T-261, T-281 | T-201 (API tasks), T-113 (engine tasks) |
| 5 | T-205, T-206, T-207, T-222, T-223, T-225, T-226, T-242, T-243, T-244, T-262, T-263, T-282 | Per task |
| 6 | T-302, T-303, T-304, T-305 | Services complete |
| 7 | T-306, T-401, T-402, T-403, T-404 | T-403 can start in wave 1 |

| Id | Task | Module | Status |
|---|---|---|---|
| T-001 | Repository skeleton, build and images | root | done |
| T-002 | Build quality gates | build-logic | done |
| T-003 | Shared test support | libs/kairos-test-support | done |
| T-101 | Identifiers, shards and domain types | kairos-domain | done |
| T-102 | Schedules: cron, interval, time zones, misfire | kairos-domain | done |
| T-103 | Retry policy, outcomes and run state machine | kairos-domain | done |
| T-104 | Workflow DAG validation and templates | kairos-domain | done |
| T-105 | Protobuf message contracts | kairos-proto | done |
| T-106 | Database migrations | kairos-migrations | done |
| T-107 | Persistence foundation and cell routing | kairos-persistence | done |
| T-108 | Messaging foundation | kairos-messaging | done |
| T-109 | Security library | kairos-security | done |
| T-110 | Outbound HTTP client and target policy | kairos-http | done |
| T-111 | Redis run lock and sent markers | kairos-engine | done |
| T-112 | Observability baseline | build-logic, deploy | done |
| T-113 | Run creation and outbox writer | kairos-engine | done |
| T-201 | API foundation | kairos-api | done |
| T-202 | Accounts APIs (1–7) | kairos-api | done |
| T-203a | Team and members APIs (8–12) | kairos-api | done |
| T-203b | API key APIs (13–15) | kairos-api | done |
| T-203c | Secrets and CA bundle APIs (16–22) | kairos-api | done |
| T-203d | Signing key and audit log APIs (23–24) | kairos-api | done |
| T-204 | Endpoint APIs (25–33) | kairos-api | done |
| T-205 | Job APIs (34–45) | kairos-api | in progress |
| T-206 | Workflow and workflow-run APIs (46–58) | kairos-api | todo |
| T-207 | Run, DLQ and stats APIs (59–68) | kairos-api | done |
| T-221 | Shard lease manager and node registry | kairos-watcher | done |
| T-222 | Fire poller | kairos-watcher | done |
| T-223 | Delayed poller | kairos-watcher | done |
| T-224 | Outbox relay | kairos-watcher | done |
| T-225 | Recovery: lease sweeper, stuck-queued sweeper, Redis failover monitor | kairos-watcher | done |
| T-226 | Singleton tasks: auto-suspend, review timeout, purger | kairos-watcher | done |
| T-241 | Dispatch consumers and back-pressure | kairos-executor | done |
| T-242 | Execution gates | kairos-executor | done |
| T-243 | Render, call, classify and publish result | kairos-executor | done |
| T-244 | Cancel listener and graceful drain | kairos-executor | done |
| T-261 | Result settler | kairos-state | todo |
| T-262 | Workflow progression | kairos-state | todo |
| T-263 | Stats aggregator | kairos-state | todo |
| T-281 | Partition manager | kairos-maintenance | todo |
| T-282 | Operator commands | kairos-maintenance | todo |
| T-301 | Mock target | tools/mock-target | todo |
| T-302 | Benchmark CLI | tools/bench-cli | todo |
| T-303 | Cross-module integration suites | tests | todo |
| T-304 | End-to-end scenarios | tools/bench-cli | todo |
| T-305 | Resilience and duplicate-execution proof | tools, scripts | todo |
| T-306 | Per-cell write test and tuning | perf | todo |
| T-401 | Helm chart | deploy/helm | todo |
| T-402 | Terraform for GCP | deploy/terraform | todo |
| T-403 | CI pipeline | .github | todo |
| T-404 | kind validation | deploy/kind | todo |

---

## 6. Phase 0: repository and tooling

### T-001 Repository skeleton, build and images
**Status:** done · **Owns:** root build files, `build-logic/`, `deploy/docker/`, `deploy/compose/`, `scripts/`, `Makefile`, module skeletons

- [x] Gradle 9.8.1 wrapper with a pinned distribution checksum; version catalog; convention plugins (`kairos.java-conventions`, `kairos.library-conventions`, `kairos.service-conventions`, `kairos.proto-conventions`)
- [x] Java 25, `-Werror`, dependency locking for every configuration, SHA-256 dependency verification
- [x] Seven library modules, four services, two jobs and two tools, each with a starting class, `application.yml` and a context test
- [x] `kairos-api`: springdoc with the `v1` group, bearer security scheme, problem details, strict JSON binding
- [x] Shared multi-stage `Dockerfile` (one Gradle build for all images, layered jars, non-root, read-only application files)
- [x] Compose stack: Postgres 18 (control and two cell databases, `sched_owner` and `sched_app`), Kafka 4.3 KRaft with all `kairos.run.*` topics, Redis 8 with an ACL user limited to `kairos:*`, all Kairos services, the mock target, the bench CLI (profile `bench`), Prometheus and Grafana (profile `observability`)
- [x] `make` targets for builds in containers, detached builds and the detached stack

### T-002 Build quality gates
**Status:** done · **Needs:** T-001 · **Owns:** `build-logic/`, `config/` (new), root `build.gradle.kts`

Add the checks every later task relies on, so standards are enforced by the build, not by review alone.

- [x] Formatting: Spotless with palantir-java-format for Java and ktlint for `*.gradle.kts` (and the Kotlin sources of `build-logic`). `make build` fails on unformatted code, and `make gradle ARGS=spotlessApply` fixes it.
- [x] Static analysis: Error Prone with NullAway (JSpecify annotations, every `dev.kairos` package null-marked) on `libs/*`, at warning-as-error level. Generated Protobuf sources are excluded.
- [x] Coverage: JaCoCo report per module plus an aggregated report (`testCodeCoverageReport` in the root project). Verification thresholds: 80% lines for `libs/*` and 70% for services, tools and jobs (excluding generated Protobuf code and `*Application` classes).
- [x] Architecture tests: ArchUnit rules from section 4.1 as a reusable rule set in `config/archunit`, compiled into every module's test source set by the `kairos.archunit-conventions` plugin and run in every module's `test` task.
- [x] Javadoc: `javadoc` runs in `check` for `libs/*` with doclint (missing comments on public API fail the build).
- [x] Gradle configuration cache enabled and verified (`org.gradle.configuration-cache=true`); `resolveAndLockAll` made compatible.
- [x] `make build` time recorded in the pull request (cold and warm cache).

**Acceptance:** an intentionally unformatted file, a missing Javadoc on a public library method, a `kairos-domain` class importing Spring, and a test that drops coverage each fail `make build` with a clear message.

### T-003 Shared test support
**Status:** done · **Needs:** T-001 · **Owns:** `libs/kairos-test-support/` (new module, test fixtures only), `settings.gradle.kts` (adding the module)

- [x] New module `libs:kairos-test-support`, consumed only through `testImplementation`.
- [x] Testcontainers definitions pinned to the same image digests as compose: `KairosPostgres` (runs `deploy/compose/postgres/01-init-databases.sh` with generated passwords, then exposes `controlDataSource()`, `cellDataSource(int)` as `sched_app`, owner variants for migration tests, and JDBC URLs and credentials), `KairosKafka` (runs `create-topics.sh` inside the container, so the topics have one definition), `KairosRedis` (starts through `start-redis.sh`, so the ACL is the same). A unit test fails if an image drifts from `docker-compose.yml`.
- [x] Containers are reused across test classes within a run (lazily started singletons), not across runs.
- [x] `MutableClock` for tests; `Awaits` helpers with short polling (Awaitility, 50 ms polls).
- [x] A JUnit extension `@KairosIntegrationTest(postgres, kafka, redis)` that starts the selected containers in parallel, resolves `KairosPostgres`/`KairosKafka`/`KairosRedis` test parameters, and registers the connection details as Spring properties (`kairos.datasource.control.*`, `kairos.datasource.cells[n].*`, `kairos.kafka.bootstrap-servers`, `kairos.redis.*`) through a context customizer, so no `@DynamicPropertySource` method is needed.
- [x] Docs in the module's `package-info.java` showing a minimal test.

**Acceptance:** a sample integration test in the module starts all three containers inside `make test` (Docker socket mounted), and runs SQL, a Kafka round trip and an authenticated Redis `SET` with the `kairos` ACL user.

---

## 7. Phase 1: foundations (libraries)

### T-101 Identifiers, shards and domain types
**Status:** done · **Needs:** T-002 · **Owns:** `libs/kairos-domain` packages `dev.kairos.domain.id`, `dev.kairos.domain.shard`, `dev.kairos.domain.model`, `dev.kairos.domain.error`

- [x] `KairosId`: UUIDv7 generation from an injected `Clock` and `SecureRandom`, with the shard (0–255) written into the low 8 bits for job, workflow, run, workflow-run and DLQ ids. `shardOf(UUID)` extracts it. Timestamp ordering is preserved.
- [x] `TestIds` in `libs/kairos-test-support` to build ids with a chosen shard
- [x] `ShardKey`: `shard = floorMod(murmur3_32_fixed(utf8(teamId.toString() + "/" + name)), 256)`. The separator and encoding are fixed forever and recorded in Javadoc. Golden test vectors are committed so the function can never change silently.
- [x] Typed ids as records (`TeamId`, `UserId`, `JobId`, `WorkflowId`, `RunId`, `WorkflowRunId`, `EndpointId`, `DlqId`) with parsing that rejects malformed input.
- [x] Names: `TeamSlug` (`^[a-z][a-z0-9-]{2,62}$`), `EndpointName` (`^[a-z][a-z0-9-]{1,62}$`), `JobName` and `WorkflowName` (≤ 128, allowed character set as in the LLD), `TaskKey`. All of them check length before matching.
- [x] Enumerations with stable database codes (smallint) for every coded column in LLD section 2: run state, trigger, target type, outcome, error class, DLQ status, schedule kind and status, misfire policy, DST policy, workflow run status, outbox topic. Each enum has `code()` and `fromCode(int)`, and unknown codes throw.
- [x] `Priority` (1–10) with tier mapping (8–10 high, 5–7 medium, 1–4 low).
- [x] `ErrorCode` catalogue matching LLD problem `type` values, and the `KairosException` hierarchy (`NotFound`, `Conflict`, `ValidationFailed`, `PreconditionFailed`, `QuotaExceeded`).

**Acceptance:** jqwik properties hold (`shardOf(newId(shard)) == shard` for all shards; ids sort by creation time); golden shard vectors pass; ArchUnit confirms no framework imports.

### T-102 Schedules: cron, interval, time zones, misfire
**Status:** done · **Needs:** T-101 · **Owns:** `dev.kairos.domain.schedule`

- [x] `ScheduleSpec` sealed hierarchy: `Immediate`, `OneTime(at)`, `Cron(expression, zone)`, `Interval(every)`, validated as in LLD section 3.6 (5-field cron via cron-utils with Unix semantics; IANA zone ids only; interval ≥ 1s; one-time at least now − 60s). `ScheduleRequest.toSpec(now)` validates the API `schedule` object and reports every invalid field (`schedule.type` `required`/`enum`, `schedule.expression` `invalid-cron`, `schedule.timezone` `format` for non-region ids such as `+05:30`, `schedule.everySec` `range` 1 s to 366 days, `schedule.at` `format`/`range`).
- [x] `ScheduleCalculator` strategy per kind: `nextFire(after, lastFiredLocal)` returns the next UTC instant plus the local time it represents (`Fire`). Cron matches local wall-clock times and maps them to instants; intervals count whole periods from an anchor (creation time or the stored `next_fire_at`).
- [x] DST handling (`dst_policy`): for a local time that falls in a gap, the default is to run at the first valid instant after the gap. For a time that occurs twice in an overlap, the default is to run once, at the first occurrence. Both are configurable per the LLD. `last_fired_local` prevents double firing in overlaps. Several local times in one gap shift to one fire; under `BOTH` the two passes of a repeated hour interleave in instant order.
- [x] `spread_ms`: a deterministic offset in [0, min(60s, period/10)) derived from the job id, to smooth top-of-minute spikes (`murmur3_32_fixed` of the UUID bytes; the cron period is the shortest gap between matches; one-time schedules get 0).
- [x] Misfire handling (`RUN_LATEST` default, `RUN_ALL` capped by `max_catchup` (default 100), `SKIP`) given `now`, `next_fire_at` and the 60s misfire threshold, returning the list of occurrences to create (`FirePlan`: fires, new `next_fire_at` and `last_fired_local`, misfired flag). `RUN_ALL` keeps the most recent `max_catchup` misfired occurrences (0 to 1,000), `RUN_LATEST` runs the latest one only when nothing falls in the on-time window, and long backlogs are found by looking back in doubling windows rather than one by one.
- [x] Preview helper used by the API: next N fire times (N ≤ 10).

**Acceptance:** tests for New York, London, Sydney, Kolkata and Lord Howe (30-minute DST) across both transitions; `0 2 * * *` on DST-start day follows the gap policy; `30 1 * * *` on DST-end day fires once; jqwik property: fire times are strictly increasing and never skip a valid occurrence.

### T-103 Retry policy, outcomes and run state machine
**Status:** done · **Needs:** T-101 · **Owns:** `dev.kairos.domain.retry`, `dev.kairos.domain.run`, `dev.kairos.domain.health`

- [x] `RetryPolicy` (`maxRetries` default 5, `baseDelaySec` 30, `maxDelaySec` 600, `maxAgeSec` 86400, strategy) with resolution order: job override, then endpoint, then team default, then platform default. The first non-null policy wins as a whole (fields are never merged). Ranges, reported as `retryPolicy.<field>` / `range`: `maxRetries` 0–20, `baseDelaySec` 1–3600, `maxDelaySec` from the base to 86400, `maxAgeSec` 60–604800 (the 7-day history retention). A series allows `maxRetries` retries after its first attempt (`attempt - series_start`).
- [x] `RetryBackoff` strategies: exponential with jitter, full jitter and fixed, with an injected random source. Defaults give roughly 15 minutes from the first failure to the DLQ. `EXPONENTIAL_JITTER` = `min(max, base * 2^(n-1) + U[0, base/2))` (930–1005s over 5 retries), `FULL_JITTER` = `U[0, min(max, base * 2^(n-1)))`, `FIXED` = `base` without jitter. One shared contract test (bounds, cap, non-decreasing bounds and same-draw delays, reproducible seeds) runs against each strategy. `RetryPlanner` takes a `Supplier<RandomGenerator>` (production: `ThreadLocalRandom::current`; jitter is not a security value) and an injected `Clock`.
- [x] `OutcomeClassifier`: maps HTTP status (honouring `successStatus` and `statusOverrides`), timeouts, connection errors before or after send, TLS failures, response-too-large and policy rejections to an `ErrorClass` plus retryable or not. `UNKNOWN_OUTCOME` applies whenever the request may have reached the target. Input is the sealed `CallResult`; output is `Classification = Success | Failure(errorClass, RETRYABLE / NON_RETRYABLE / UNKNOWN_OUTCOME)`. Overrides (`SUCCESS`, `RETRY`, `FAIL`) win over `successStatus` (`2xx` or exact statuses, parsed by hand, at most 10; at most 50 overrides). Defaults: 429 `HTTP_429` and 5xx `HTTP_5XX` retryable; 408 and 425 `HTTP_4XX` retryable; any other non-success status (including unlisted 1xx/2xx/3xx, since redirects are off) `HTTP_4XX` not retryable. A timeout after send is `TIMEOUT` and a dropped connection after send `UNKNOWN_OUTCOME`, both with the unknown-outcome follow-up; before send both are `CONNECT_FAILED` and retryable. DNS and TLS failures are retryable; target-policy and render failures are not. An oversized response or a missing required output fails as `RESPONSE_TOO_LARGE` / `OUTPUT_MISSING` (not retryable) only when the status means success; otherwise the status decides.
- [x] `UnknownOutcomePolicy` decision: `RETRY_WITH_IDEMPOTENCY_KEY` gives a retry carrying the same `Idempotency-Key` (the run id), and `REVIEW` gives a dead letter with reason `UNKNOWN_OUTCOME`. `RetryPlanner.plan` returns `RetryDecision = Retry(idempotencyKey, nextAttempt, delay, dueAt) | DeadLetter(reason)`; an exhausted series dead-letters with the last error class, a retry due past `maxAgeSec` with `MAX_AGE_EXCEEDED`, and a `Retry-After` hint lengthens the delay up to `maxDelaySec`.
- [x] `RunState` machine (`WAITING`, `QUEUED`, `RUNNING`, `DELAYED` and the terminal outcomes) with an explicit allowed-transition table, plus fence increment rules (every dispatch and claim increments). `RunStatus` joins the four states with the final outcomes (`FAILED_RETRYING` and `EXECUTOR_LOST` only end an attempt). `RunStateMachine.transition` throws `IllegalRunTransitionException` outside the table; entering `QUEUED` (including the stuck-queued re-dispatch `QUEUED → QUEUED` and the lease sweeper's `RUNNING → QUEUED`) or `RUNNING` increments the `Fence`; a run created `QUEUED` starts at fence 1. `SUCCEEDED`, `CANCELLED` and `SKIPPED` are absorbing; `DEAD_LETTERED → DELAYED/QUEUED` (redrive, workflow retry) and `UPSTREAM_FAILED → WAITING` (workflow retry) reopen a run and start a new retry series.
- [x] `JobHealth` rules: a 10-outcome bitmask, consecutive dead count, and auto-pause after `autoPauseAfter` (default 3, 0 disables). Only `SUCCEEDED` (0 bit) and `DEAD_LETTERED` (1 bit) count, newest in bit 0; `unhealthySince` is set while at least 6 of the last 10 failed. `AutoPause` (0–100, `autoPauseAfter` / `range`) pauses when a dead letter brings the streak to the threshold; `afterResume()` restarts the streak.
- [x] `EndpointSuspendRule`: ≥ 50 dead letters in 5 min, or ≥ 80% failures over ≥ 200 attempts, with a 10-minute cool-down. Both thresholds use the same 5-minute window of `endpoint_health_minute` counters (`EndpointHealthWindow`); the cool-down runs from `status_changed_at`, and the result is `SuspendDecision = Keep | Suspend(reason) | CoolingDown(reason, until)`.

**Acceptance:** a table-driven test covers every status code class and error type; jqwik shows backoff stays in bounds and is non-decreasing in expectation; illegal state transitions throw.

### T-104 Workflow DAG validation and templates
**Status:** done · **Needs:** T-101 · **Owns:** `dev.kairos.domain.workflow`, `dev.kairos.domain.template`

- [x] `WorkflowSpec` model: tasks (key, endpoint name, request values, outputs mapping), edges, `onTaskFailure` (`BLOCK_FOR_REVIEW` default, `FAIL_FAST`).
- [x] Validation: acyclic (Kahn's algorithm, reporting the cycle path), ≤ 200 tasks, ≤ 10 levels, unknown task references, duplicate keys, unreachable tasks, and at least one root.
- [x] `WorkflowPlan`: topological levels, roots, `remaining_deps` per task, and a downstream adjacency list, stored with the version.
- [x] Template engine (no expression language and no reflection): a hand-written tokenizer for `{{ tasks.<task>.outputs.<name> }}` and `{{ run.id }}` only. Input length is capped and nested braces are rejected.
- [x] Template validation: a placeholder may only reference declared outputs of ancestor tasks, and never the URL host or scheme. Placeholders are allowed in path parameters, query, headers and body values.
- [x] Rendering with encoding per target: path segments percent-encoded, query values encoded, header values rejected if they contain CR or LF, and JSON body values inserted as JSON values (never as raw text). Sensitive outputs render only into headers or body, never into the URL.
- [x] Output extraction spec: `from` `BODY` (a JSON Pointer subset, `$.a.b[0]`), `HEADER` or `STATUS`; `required`; `sensitive`; and the 24 KB total cap per task.

**Acceptance:** DAG fixtures (diamond, wide fan-out of 200, chain of 10, chain of 11 rejected, self-loop and cycle rejected with path); template injection attempts (CR/LF, `../` in a path, `}}{{`, a host placeholder) are rejected; jqwik: any valid random DAG passes, and adding a back edge fails.

**Verification:** Docker `make bg BG_NAME=kairos-gradle-t104 ARGS="spotlessApply build check"` passed: 2,136 tests, no failures/errors/skips; domain line coverage 99.55%. Includes 300 generated DAG trials and 100 back-edge trials, immutable plan checks, typed JSON insertion, output bounds and injection rejection. No production dependencies, migrations or service wiring changed. T-206 resolves endpoint output declarations before domain validation; T-243 owns JSON adaptation, sealing sensitive outputs and HTTP assembly without double encoding.

### T-105 Protobuf message contracts
**Status:** done · **Needs:** T-002 · **Owns:** `libs/kairos-proto/src/main/proto/kairos/v1/*`, `dev.kairos.proto`

- [x] `dispatch.proto`: run id, shard, fence, attempt, infra attempt, team id, target type, definition id and version, target id, task key, endpoint id and version, priority, timeout, scheduled and due times, request snapshot (rendered path params, query, headers, body bytes; never secrets, only secret references), trace parent.
- [x] `result.proto`: run id, shard, fence, attempt, outcome, error class, HTTP status, latency, started and finished times, executor id, redacted error (≤ 512), redacted response snippet (≤ 1 KB), extracted outputs (non-sensitive) plus sealed sensitive outputs, sent-marker flag.
- [x] `cancel.proto`, `completed.proto`, `dead.proto` with the fields the LLD lists for subscribers and alerting.
- [x] `package kairos.v1; option java_multiple_files = true; option java_package = "dev.kairos.proto.v1";` Comments on every message and field.
- [x] A compatibility check task that compares against the last released descriptor set and fails on removed or renumbered fields (descriptor set committed under `libs/kairos-proto/compat/`).

**Acceptance:** round-trip tests for every message; the compatibility check fails when a field number is changed in a test branch.

### T-106 Database migrations
**Status:** done · **Needs:** T-002 · **Owns:** `jobs/kairos-migrations/**`

- [x] Flyway (from the Spring Boot BOM) with two locations: `db/control` and `db/cell`. The job migrates the control database, then every cell listed in its configuration (`KAIROS_CELL_<n>_DB_URL`), then exits with code 0. Any failure exits non-zero. Flyway is driven directly, one instance per database, with `clean` disabled and an `afterMigrate` callback that takes the application role's access to the history table away.
- [x] Control DDL for every table in LLD section 2.2 with the stated types, primary keys, unique constraints and checks (slug and name patterns, value ranges). `audit_log` is range-partitioned by day.
- [x] Cell DDL for every table in LLD section 2.3:
  - `run_inflight` and `outbox` hash-partitioned by `shard_id` (16 partitions);
  - `run_history` and `task_outputs` range-partitioned by day;
  - the unique occurrence constraint `(target_id, task_key, scheduled_at)`, declared as `(shard_id, target_id, task_key, scheduled_at)` because a unique constraint on a partitioned table must include the partition key (the target id embeds the shard, so the two are equivalent).
- [x] Indexes for every hot query: `schedules (shard_id, next_fire_at) WHERE status = 1`, `run_inflight (shard_id, state, due_at)`, `run_inflight (owner_node, lease_until)`, `outbox (shard_id, available_at)`, the job and workflow name uniqueness among non-deleted rows (partial unique indexes), `dead_letters (team_id, status, created_at)`, and the list endpoints' sort keys.
- [x] Autovacuum settings per hot table (`outbox`, `run_inflight`, `schedules`): lower scale factors and a higher cost limit, set with `ALTER TABLE ... SET (...)`.
- [x] Seed `cell_map` for the local layout (cell 0 = shards 0–127, cell 1 = 128–255) from configuration, idempotently.
- [x] Initial partitions: today plus 3 days ahead (UTC days, `kairos.migrations.partition-days-ahead`), created through `scheduler.create_day_partition(table, day)`, which T-281 reuses. `run_history`, `task_outputs` and `audit_log` also get a `DEFAULT` partition, so an old occurrence (a backfill) never fails its insert.
- [x] Grants: tables created by `sched_owner` are usable by `sched_app` through the default privileges set in `01-init-databases.sh`. The migration verifies this and fails if `sched_app` lacks DML on any table.
- [x] `kairos-migrations` test: runs all migrations twice against Testcontainers (idempotent), then asserts that every LLD table and column exists with the documented type (a test reads `information_schema` and compares with a checked-in expected list).
- [x] Platform keyset bootstrap (follow-up for T-109): after every database is migrated, the job calls `PlatformKeysetBootstrap.ensureJwtSigningKeyset()` through an imported `PlatformKeysetConfiguration`, backed by a `controlJdbcClient` on the control database as the schema owner. Insert-if-absent, so a second or concurrent run never rotates or duplicates the keyset; only the outcome is logged. The KEK is `KAIROS_LOCAL_KEK` under the `local` profile and the Cloud KMS key `KAIROS_KEK_URI` under every other profile (T-401 sets it). `JwtKeysetBootstrapIntegrationTest` checks that the keyset opens as an access-token key ring and that a second run leaves the row unchanged.

**Acceptance:** `make up` runs the migrations container to exit code 0, and `\dt scheduler.*` in each database shows the LLD tables; the schema test passes.

### T-107 Persistence foundation and cell routing
**Status:** done · **Needs:** T-101, T-106, T-003 · **Owns:** `libs/kairos-persistence/**`

- [x] `DataSourceRegistry`: one HikariCP pool for the control database and one per cell, built from `kairos.datasource.control.*` and `kairos.datasource.cells[n].*` (`PersistenceProperties`; each service maps `KAIROS_CONTROL_DB_URL`, `KAIROS_CELL_<n>_DB_URL`, `KAIROS_DB_USERNAME` and `KAIROS_DB_PASSWORD` onto them in its `application.yml`). Pool sizes are configurable per service (`kairos.datasource.pool.*`, with an optional `maximum-pool-size` per database). Cell `n` is the data source `cell-n` of `cell_map`. Pools start without connecting, so a database that is down fails readiness instead of startup.
- [x] `CellRouter`: shard → cell from `cell_map`, cached with Caffeine and refreshed every 30s (`kairos.datasource.cell-map-refresh`). `forShard(int|Shard)`, `forId(UUID|ShardedId)`, and `allCells()` for fan-out reads. Reloads run in the background; a failed or invalid reload (gap, overlap, unknown data source) keeps the current layout, so a control database outage never stops cell traffic.
- [x] `CellTransactions`: runs a callback in one transaction on the cell of a shard (`TransactionTemplate` per call on the cell's `DataSourceTransactionManager`), with configurable isolation (default read committed) and a transaction deadline that becomes each statement's JDBC query timeout (works behind PgBouncer, unlike `SET statement_timeout`). Statements outside a transaction get `kairos.datasource.transaction.statement-timeout` (5s). Opening a transaction on a second database from inside one throws.
- [x] `ControlTransactions`: the same for the control database.
- [x] `JdbcClient` beans per database (`controlJdbcClient`; cell clients through `Cell.jdbc()`), plus helpers for batch inserts (`Database.batchUpdate`, named parameters, chunked by `kairos.datasource.transaction.batch-size`) and `RETURNING` mapping (`Database.updateReturning`).
- [x] Error translation (`SqlErrorTranslator`): unique violation → `ConflictException` with the code registered for the violated constraint in `ConstraintConflicts` (`INVALID_STATE` otherwise), serialization failure or deadlock → `RetryableDatabaseException` with a bounded retry helper (`TransientRetry`: three attempts, delay in [d/2, d] with d = 20 ms × 2^(n−1)), used by `executeWithRetry`.
- [x] Readiness health indicators per database: one `kairosDatabases` composite with a child per database (`control`, `cell-0`, ...). A service includes `kairosDatabases` or only the children it needs (`kairosDatabases/cell-0`) in its readiness group, so a cell being down marks only the services that need it as not ready.
- [x] Fan-out query helper for team-scoped list endpoints across both cells (`FanOutQuery`): parallel reads on virtual threads with a deadline, k-way merge by sort key, `limit + 1` per cell, and the last row's key as the cursor (the API signs it). A cell that fails or answers out of order fails the read; partial pages are never returned.
- [x] Metrics: pool usage and wait time (`hikaricp_connections_*`, `pool` = `kairos-control`, `kairos-cell-0`, ...), and transaction duration per database (`kairos_db_transaction_duration_seconds`, `cell` = `control`, `cell-0`, ..., with histogram buckets).

**Acceptance:** integration tests route ids from shards 0, 127, 128 and 255 to the right database; a duplicate insert raises `ConflictException`; the fan-out list merges two cells in order with a stable cursor.

### T-108 Messaging foundation
**Status:** done · **Needs:** T-105, T-003 · **Owns:** `libs/kairos-messaging/**`

- [x] `KairosTopic` enum with names, key type, outbox code and priority mapping (LLD section 4.2). Each outbox-fed topic refers to its `OutboxTopic` constant in `kairos-domain` (the codes the outbox writer stores), and the enum refuses to load if a name differs, so they cannot drift; only `kairos.run.result` has no outbox code. Keys are 16-byte ids (`KeyType`: target id for dispatches and results, run id for cancels, definition id for completed and dead events, as the outbox writer keys them). Partitions and retention are checked against the compose topic script in a test.
- [x] Protobuf `Serializer` and `Deserializer` per message type. The deserializer never throws on a bad payload; it returns a poison marker instead. `MessageType` holds one serializer and deserializer per contract; `Decoded` is `Valid | Poison(reason, size)` with reasons `EMPTY`, `TOO_LARGE` and `MALFORMED`. Values are capped at 256 KiB (the `outbox` payload limit): the serializer refuses larger messages and the deserializer marks them poison before parsing.
- [x] A producer factory with the settings in section 4.7, plus a `KafkaPublisher` that sends a batch and returns per-record futures (used by the outbox relay). `KafkaClientFactory`: `acks=all`, idempotence, lz4, bounded `delivery.timeout.ms` (default 30s, 5s to 5 min), 1 MiB requests; a synchronous refusal fails only its record's future; the `traceparent` header is written from the stored value.
- [x] A consumer factory (manual commit, cooperative-sticky, configurable `max.poll.records`) and a `PartitionFlowControl` helper for pause/resume at the high and low water marks. Consumers read `read_committed` with raw keys and `Decoded` values; `PartitionFlowControl` pauses every assigned partition at the high mark, re-pauses partitions a rebalance adds, and resumes at the low mark. `PoisonMessages` counts `kairos_messages_poison_total` (labels `topic`, `reason`), logs topic, partition, offset, reason and size without the payload, and skips the record.
- [x] `traceparent` header helpers that inject and extract with Micrometer Tracing. `TraceHeaders` injects and extracts through the service's `Propagator` (no-op when tracing is off) and reads and writes the raw stored value for the outbox relay, with the same validity rules as the engine's `TraceParents`.
- [x] Kafka client metrics bound to Micrometer. Every producer and consumer from the factory binds `KafkaClientMetrics` and unbinds it on close.

Connection settings come from `kairos.kafka.*` (`KafkaProperties`): bootstrap servers, `PLAINTEXT` (local only), `SSL` or `SASL_SSL` with `PLAIN` or SCRAM, user name and password from the environment (validated so they cannot break out of the JAAS line, never logged), an optional PEM trust certificate, and producer and consumer tunables. Services import `MessagingConfiguration` (client factory, a shared publisher, poison handler, trace headers).

**Acceptance:** Testcontainers round trip for every topic; a poison message is counted and skipped without stalling the partition; pause and resume work under load in a test.

### T-109 Security library
**Status:** done · **Needs:** T-101 · **Owns:** `libs/kairos-security/**`; adds secrets to `deploy/compose/.env.example`, `scripts/dev-env.sh` and the env blocks of `kairos-api`, `kairos-executor` and `kairos-migrations`

- [x] `PasswordHasher`: Argon2id through Bouncy Castle (memory 19 MiB, 2 iterations, parallelism 1, 16-byte salt, 32-byte hash, PHC string format), with `needsRehash` for parameter upgrades. `PasswordPolicy`: 12–128 characters, not equal to the email, and rejected if it appears in a bundled list of common passwords. Passwords are NFC-normalised before hashing; costs read back from a stored hash are capped (256 MiB, 16 iterations, 16 lanes) so a planted hash cannot exhaust memory; `verifyDummy` spends one verification for unknown emails so login timing does not reveal accounts. Length is counted in code points; violations carry the codes `too-short`, `too-long`, `same-as-email` and `common-password` with the LLD detail. Costs can only be raised through `kairos.security.password.*`.
- [x] `AccessTokenService`: ES256 JWT through Nimbus (15 minutes; claims `sub`, `team`, `sid`, `iat`, `exp`, `jti`, `iss`, `aud`; header `kid`). Verification checks the algorithm allow-list (ES256 only), `iss`, `aud`, expiry with 30s skew, and that `kid` is known. JWKS export of public keys only. The keys come from the platform `JWT_SIGNING` Tink keyset (ECDSA P-256, IEEE P1363, no prefix) through `JwtKeyRing`; `kid` is `k` plus the Tink key id in hex. `iat` in the future (beyond the skew) is rejected; tokens longer than 4 KB are rejected before parsing; keys embedded in the header are never used. Results are a sealed `TokenVerification` (`Valid` or `Invalid` with a reason for metrics). `rotateJwtSigningKeyset` keeps the previous key so issued tokens verify until they expire; `replaceKeys` swaps the ring at run time.
- [x] `RefreshTokenService`: 32 bytes from `SecureRandom` encoded base64url. The SHA-256 hash is stored. A rotation family tracks the session, and reuse of a `ROTATED` token revokes the family. Tokens carry the `rt_` prefix of the LLD examples. Storage is a `RefreshSessionStore` port (T-201 implements it on `user_sessions`) whose `rotate` marks the old row and inserts the new one atomically; losing a concurrent rotation also counts as reuse. Logout revokes the family; `revokeOthers` serves password change.
- [x] `ApiKeyService`: format `kairos_<keyId 16 alphanumerics>_<secret 32 bytes base64url>`. Storage is HMAC-SHA256 under a pepper with `pepper_version`; verification is constant-time; pepper rotation is supported (try current, then previous). The stored `pepper_version` selects the pepper; a match under the previous pepper returns `MATCH_OUTDATED_PEPPER` so the caller re-hashes with `hash()`. Parsing is structural (length and alphabets, no regular expressions).
- [x] `KeysetService` (Tink): AEAD keysets per team and purpose (`SECRETS`, `OUTPUTS`) and the platform signing keyset, sealed by a KEK. The KEK comes from Cloud KMS in production (`tink-gcpkms`) and, locally, from `KAIROS_LOCAL_KEK` (32 random bytes, hex, generated by `make env`). The local KEK adapter loads only when the `local` profile is active, and refuses to start otherwise. Sealing uses associated data `team_keysets|<teamId>|<purpose>` or `platform_keysets|<purpose>`, so a keyset copied to another row does not open, and a keyset whose `kek_uri` differs from the configured KEK is refused. The team `SIGNING` keyset is HMAC-SHA256 (no prefix) built from 32 `SecureRandom` bytes, exported once as `ksig_v<n>_<base64url>`; the Tink key id is the version, and rotation keeps only the previous key. Opened keysets are cached (Caffeine, by owner, purpose and ciphertext hash; `kairos.security.keyset-cache.*`). Outside the `local` profile a configured local key also fails startup.
- [x] `SecretCipher`: encrypts and decrypts team secrets and sensitive outputs with associated data (`team_id|name|version`) so ciphertexts cannot be swapped between rows. Outputs use `team_id|run_id|output_name` and the `OUTPUTS` keyset; using the wrong purpose is rejected; plaintext is capped so the ciphertext fits the 16 KB column.
- [x] `RequestSigner`: `X-Kairos-Signature` (HMAC-SHA256 over method, path, timestamp, run id and body hash) with `X-Kairos-Timestamp`, using the team's signing keyset. Signature verification sample code goes in the Javadoc for target teams. Header value `v<version>=<hex>` over `METHOD\nPATH?QUERY\nTIMESTAMP\nRUN_ID\nhex(SHA-256(body))`; the run id is the `Idempotency-Key` the target already receives. Control characters and non-ASCII in the method or target are refused. `RequestSignatures` is the reference verifier (key versions, 5-minute tolerance, constant-time) for the mock target.
- [x] Bootstrap: `kairos-migrations` creates the platform JWT signing keyset if it is missing (insert-if-absent, so concurrent runs are safe). `PlatformKeysetBootstrap.ensureJwtSigningKeyset()` on `JdbcPlatformKeysetStore` (`ON CONFLICT DO NOTHING`, tested with 8 concurrent runs) and `PlatformKeysetConfiguration`; the migrations job calls it after migrating (see T-106).
- [ ] New secrets added to `dev-env.sh` and `.env.example`: `KAIROS_LOCAL_KEK`, `KAIROS_API_KEY_PEPPER`, `KAIROS_CURSOR_HMAC_KEY`. The script generates them for new files and appends them to an existing file without touching other values; the compose env blocks of `kairos-api` (all three), `kairos-executor` and `kairos-migrations` (`KAIROS_LOCAL_KEK`) set them with `SPRING_PROFILES_ACTIVE=local`. The example file still needs the three names added.

**Acceptance:** known-answer tests for Argon2id; a JWT signed with a different key, `alg: none`, HS256 or an expired timestamp is rejected; refresh reuse revokes the family; ciphertext with swapped associated data fails to decrypt; no secret value appears in any `toString()` (a test checks).

### T-110 Outbound HTTP client and target policy
**Status:** done · **Needs:** T-101 · **Owns:** `libs/kairos-http/**`

- [x] `TargetPolicy` from configuration (`kairos.target-policy.*`): HTTPS only; allowed DNS suffixes (for example `.svc.cluster.local`, `.internal`) and exact hosts; allowed CIDRs; allowed ports. The fixed deny list cannot be configured away: loopback, link-local (including `169.254.169.254`), metadata hostnames, the Kubernetes API service address, `0.0.0.0/8`, multicast, and the Kairos infrastructure addresses provided at startup. Defaults: suffixes `.svc.cluster.local` and `.internal`, CIDRs `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16` and `fc00::/7`, ports 443 and 8443; every resolved address must also lie in an allowed CIDR. The fixed list also covers `240.0.0.0/4`, the IPv6 unspecified, loopback, link-local, site-local and multicast ranges, and IPv6 forms that embed IPv4 (IPv4-compatible, NAT64, 6to4, Teredo); `localhost`, `metadata`, `metadata.google.internal`, `metadata.goog` and `kubernetes.default.svc.*` are denied by name. Infrastructure comes from `kairos.target-policy.infrastructure-hosts` (denied by name and by the addresses resolved at startup), `infrastructure-cidrs` and `KUBERNETES_SERVICE_HOST`. IPv4-mapped IPv6 is folded to IPv4. IPv4 literals must be canonical dotted quads; any other host whose last label starts with a digit (`127.1`, `2130706433`, `0x7f.0.0.1`, `0177.0.0.1`) is refused, as are IPv6 zone ids, user info, fragments, trailing dots, non-ASCII names and authorities that `java.net.URI` reads differently.
- [x] `ValidatingDnsResolver` for Apache HttpClient 5. It resolves, checks every address against the policy, and returns only checked addresses, so the connection goes to the checked IP. TLS hostname verification still uses the original host name (SNI and certificate check). This blocks DNS rebinding. One refused address refuses the whole host; a refusal is a `TargetNotAllowedException` (a subclass of `UnknownHostException`), so it is told apart from a DNS failure. Reverse lookups are never made.
- [x] `HttpClientFactory`: a pooled classic client (virtual threads); connect timeout 3s; response timeout = the endpoint `timeoutMs`; redirects disabled; TLS 1.2+ with default hostname verification; an optional per-team CA bundle merged with the default trust store (never replacing hostname checks); a connection pool per route with limits. The connection operator registers `https` only; HTTP/1.1 with at most 100 response headers of 16 KB per line; no proxy; automatic retries, cookies, auth challenges and decompression are off. Besides the response (read-inactivity) timeout, the exchange after the send is cut off at the endpoint timeout, so a trickling target cannot hold a call. CA-bundle clients are cached by team, bundle name and PEM hash; pool sizes come from `kairos.http.*`.
- [x] `ResponseReader`: reads at most 256 KB, then aborts with `RESPONSE_TOO_LARGE`; decodes JSON for output extraction only when the content type is JSON. The rest of an oversized body is never drained (the connection is discarded). JSON (`application/json`, `application/*+json`) is parsed with nesting, number and string limits and duplicate keys refused; a body that does not parse simply has no tree.
- [x] `AuthApplier` strategies: `NONE`, `BASIC`, `BEARER`, `API_KEY` (header or query name from the spec), and `OAUTH2_CLIENT_CREDENTIALS` (token fetched through the same validating client, cached until 60s before expiry, keyed by team, endpoint version and secret version). Secrets arrive as `AuthMaterial`, already decrypted, with redacted `toString()`; CR/LF and control characters are refused in every header name and value. The API key spec is `{location: HEADER|QUERY, name}`; the OAuth 2.0 spec is `{tokenUrl, scope}` with `client_secret_basic` client authentication; a token without `expires_in` is used once and not cached; a token-endpoint failure is an `AuthFailedException` carrying the classifier input (the target is never sent). `Authenticator.onUnauthorized` drops a cached token after a 401.
- [x] `SendObserver` hook called just before the request bytes are written (used by T-242 for the send-time guard and sent marker), and a precise "sent or not sent" signal on failure. It runs in the request executor, after connect and TLS and before the request line; any exception vetoes the send (`HttpCallOutcome.NotSent`, not an attempt), and a call sends at most once. Every `HttpCallOutcome` reports `requestSent()` and maps to T-103's `CallResult`.
- [x] Redaction helpers for errors and response snippets (strip `Authorization`, `Cookie`, `Set-Cookie`, configured secret headers, and any configured output marked sensitive). Bearer- and Basic-shaped credentials are masked too; secrets are removed before truncation (error 512 characters, snippet 1 KB of UTF-8) and control characters are replaced.

**Acceptance:** tests with a local TLS server (Testcontainers or an in-JVM server) show that `http://` is refused, a host resolving to `127.0.0.1` or `169.254.169.254` is refused, a rebinding resolver (first public, then private) is refused, a 302 is not followed, a 300 KB body is cut off, a wrong certificate host fails, and a team CA bundle is trusted only for that client.

### T-111 Redis run lock and sent markers
**Status:** done · **Needs:** T-003 · **Owns:** `libs/kairos-engine` package `dev.kairos.engine.lock`

- [x] Lettuce client configuration: ACL username and password from environment variables, command timeout 500 ms, and TLS when `kairos.redis.tls=true` (production). Bound from `kairos.redis.*` (`RedisProperties`: `host`, `port`, `username`, `password`, `tls`, `trust-certificate`, `command-timeout`, `connect-timeout`, `reconnect-delay`, `node-id`); TLS verifies chain and host name (TLS 1.3/1.2) against the JDK trust store or a PEM CA file. Commands are rejected at once while disconnected; the first connection opens in the background so a service starts while Redis is down. `RunLockConfiguration` is imported explicitly by the services (no auto-configuration); the node id defaults to `HOSTNAME` (validated) or a random id.
- [x] Lua scripts, loaded once and called by SHA with automatic reload:
  - `acquire`: `SET key token NX PX ttl`; the token encodes fence, node id and random bytes;
  - `remaining`: returns the TTL left only if the caller still owns the lock (used by the send-time guard);
  - `release`: compare-and-delete;
  - `extend`: compare-and-extend, for long calls.
- [x] `RunLockPort` and `SentMarkerPort` interfaces, plus Lettuce adapters. Keys follow LLD section 4.3 (`kairos:lock:run:{runId}`, `kairos:sent:{runId}:{attempt}:{infraAttempt}` with a 48h TTL). Sealed results (`AcquireResult`, `RemainingResult`, `LockInspection`) and enums (`ReleaseResult`, `ExtendResult`, `MarkResult`, `MarkerCheck`); `RunLockPort.inspect` reads the holder for the lease sweeper. Lock TTLs are capped at 1h.
- [x] Fail-closed behaviour: any Redis error or timeout makes `acquire` return `UNAVAILABLE`. Callers then do not call the target. The other operations fail closed the same way; nothing throws for a Redis failure.
- [x] `RedisTopologyProbe`: reads the replication id and role (`INFO replication`, allowed by the ACL) for the failover monitor (T-225).
- [x] Metrics: acquire outcome counts, script latency, unavailable count (`kairos_run_lock_acquire_total{outcome}`, `kairos_redis_script_duration_seconds{script}`, `kairos_redis_unavailable_total`, counted on every failed command).

**Acceptance:** Testcontainers tests show that two concurrent acquires yield exactly one owner; release by a non-owner is a no-op; `remaining` returns nothing after expiry; with Redis stopped, `acquire` returns `UNAVAILABLE` within the timeout.

### T-112 Observability baseline
**Status:** done · **Needs:** T-002 · **Owns:** `build-logic` (`kairos.observability-conventions`, applied by the service conventions), `config/observability/` (package `dev.kairos.observability`, compiled into every application so no application depends on another), `deploy/compose/prometheus/`, `deploy/compose/grafana/`

- [x] Micrometer Tracing with the OpenTelemetry bridge in every service. OTLP export is configurable and off by default locally; sampling is configurable (`kairos.observability.tracing.*`: `export-enabled`, `endpoint`, `sampling-probability`, default 0.1). W3C `traceparent` propagation.
- [x] Common metric tags (`service`, `instance`, `cell` where relevant), and an SLO histogram for `kairos_schedule_lag_seconds` with buckets around 2s (meter `kairos.schedule.lag`; buckets 100 ms to 60 s, dense between 1 s and 3 s, configurable through `kairos.observability.schedule-lag-slo`).
- [x] Log redaction: an ECS field filter (`RedactingLogCustomizer`) masks every value under a key containing `password|passwd|pwd|secret|token|authorization|apikey`, `key=value` and JSON pairs in messages and stack traces (any case and spacing, escaped quotes), `Bearer ...`/`Basic ...` credentials and URL user-info passwords. Unit tests and a test that starts an application with ECS logging prove it.
- [x] Prometheus alert rules (`deploy/compose/prometheus/alerts.yml`) for the HLD alerts: schedule lag p99 > 2s, outbox backlog age > 10s, Redis unavailable, endpoint suspended, job failing most recent runs, DLQ growth, executor saturation, login-failure spike, plus a scrape-down alert. `promtool test rules` cases in `alerts-test.yml`.
- [x] Grafana dashboards provisioned from JSON: Overview (fires/s, executions/s, lag p50/p99, outcomes), Watcher (leases, poll durations, outbox age), Executor (in-flight, gates, call latency by outcome, breaker states), State (batch size, settle latency), Databases (pool usage, transaction time).

Metric names the alerts and dashboards expect; the tasks that emit them use exactly these names and labels (section 4.9):

| Metric (Prometheus name) | Type | Labels | Emitted by |
|---|---|---|---|
| `kairos_schedule_lag_seconds` | timer (histogram) | `priority` | T-243 |
| `kairos_fires_total`, `kairos_misfires_total` | counter | `cell`; misfires add `policy` | T-222 |
| `kairos_poll_duration_seconds`, `kairos_poll_batch_size` | timer, summary | `poller`, `cell` | T-222, T-223 |
| `kairos_shard_leases_owned`, `kairos_shard_lease_changes_total` | gauge, counter | `outcome` on changes | T-221 |
| `kairos_shard_poller_duration` | timer | `poller`, `cell` | T-221 |
| `kairos_outbox_oldest_due_seconds`, `kairos_outbox_backlog_rows`, `kairos_outbox_published_total` | gauge, gauge, counter | `cell`; published adds `topic` | T-224 |
| `kairos_executor_inflight`, `kairos_executor_capacity` | gauge | `priority` on in-flight | T-241 |
| `kairos_executor_deferrals_total` | counter | `reason` | T-241 |
| `kairos_execution_gate_total` | counter | `gate`, `outcome` | T-242 |
| `kairos_executor_calls_total`, `kairos_executor_call_duration_seconds` | counter, timer | `outcome`, `error_class`, `priority` / `tier` | T-243 |
| `kairos_settle_batch_size`, `kairos_settle_duration_seconds` | summary, timer | none | T-261 |
| `kairos_retries_scheduled_total`, `kairos_dlq_entries_created_total`, `kairos_dlq_open_entries` | counter, counter, gauge | `error_class`; DLQ adds `cell` | T-261 |
| `kairos_workflow_tasks_queued_total` | counter | none | T-262 |
| `kairos_endpoint_suspensions_total`, `kairos_jobs_failing_recent_runs` | counter, gauge | `endpoint_id` on suspensions | T-226, T-263 |
| `kairos_redis_unavailable_total` | counter | none | T-111 |
| `kairos_db_transaction_duration_seconds` | timer | `cell` | T-107 |
| `kairos_login_failures_total` | counter | none | T-202 |

**Acceptance:** `make up-all` shows all dashboards with live data from the running services, and Prometheus loads the alert rules without errors.

### T-113 Run creation and outbox writer
**Status:** done · **Needs:** T-101, T-103, T-105, T-107 · **Owns:** `libs/kairos-engine` packages `dev.kairos.engine.run`, `dev.kairos.engine.outbox`

The single place where runs and their outbox rows are created, used by the API (immediate runs, run now, backfill, redrive), the fire poller, the delayed poller and the settler.

- [x] `RunFactory`: builds `run_inflight` rows for each trigger (schedule, immediate, manual, backfill, redrive), pinning definition and endpoint versions and computing `due_at` (spread, pacing). A redrive re-creates the run under its original run id with a new attempt series (`series_start`, `series_started_at`). Input is a `RunTarget` (team, job or workflow task, pinned definition and endpoint versions, priority, `maxLateness`, `CallSpec`); output is a `PlannedRun(InflightRun, CallSpec)`. Schedule: `due_at = scheduled_at + spread` (0–60s); due within the 5-second dispatch horizon (the fire poller's look-ahead) it is created `QUEUED` at fence 1, later `DELAYED` at fence 0. Immediate and manual: `QUEUED`, due now. Backfill: `DELAYED`, the i-th occurrence due `i / rate` seconds from now (default 100/s, at most 1K/s and 1,000 occurrences per call). Redrive: `DELAYED` and due now so the delayed poller paces it, attempt `last + 1` starting the series, and the fence continues from the dead-lettered run's fence so its old dispatch messages can never match again. `waiting(...)` creates `WAITING` workflow tasks with `remaining_deps`. Times are truncated to microseconds so the occurrence key matches the database.
- [x] `RunInserter`: batch `INSERT ... ON CONFLICT (target_id, task_key, scheduled_at) DO NOTHING RETURNING run_id`, so an occurrence is created at most once. One multi-row statement per 500 rows (named parameters numbered by row); the conflict target is `(shard_id, target_id, task_key, scheduled_at)`, the partitioned form of the occurrence constraint (T-106). The integration test checks the plan (`Conflict Resolution: NOTHING`, arbiter index, no sequential scan).
- [x] `OutboxWriter`: writes outbox rows in the caller's transaction (dispatch to the priority topic, cancel, completed, dead). It must be called inside a `CellTransactions` callback, and throws otherwise. `OutboxMessage` factories build rows from `Dispatch`, `Cancel`, `Completed` and `Dead` with the LLD section 4.2 keys (target id, run id, definition id) and validate the `outbox` constraints; `CellTransactionGuard` checks that the shard's cell pool is bound to the thread's transaction, so a transaction on the control database or the other cell does not count. Rows are a JDBC batch with time-ordered ids in the row's shard; `trace_parent` is kept only when it is a valid W3C value (`TraceParents`).
- [x] `DispatchMessageBuilder`: builds the Protobuf dispatch payload from the run and the pinned versions (no secrets) and increments the fence. `build` serialises a run created `QUEUED` (fence already 1); `redispatch` moves a `WAITING`, `DELAYED`, `QUEUED` or `RUNNING` run to `QUEUED` through `RunStateMachine` and returns the row with its new fence plus the message, for the delayed poller and sweepers. `CallSpec` rejects snapshots carrying `Authorization`, `Proxy-Authorization`, `Cookie` or `Set-Cookie` headers; credentials travel only as `secret_refs`.
- [x] Postgres `NOTIFY kairos_fire_<cell>` on new immediate runs, so the poller reacts without waiting for its next cycle. `FireNotifier` sends `pg_notify` with the shard number as payload (delivered on commit, dropped on rollback) when `RunCreator` creates a `QUEUED` run that is already due and was not created by the fire poller (immediate, manual, redrive). `RunCreator` ties it together: insert, one dispatch row per created `QUEUED` run with `available_at = due_at`, notify; it returns `RunCreation(created, duplicates)`.

**Acceptance:** integration tests show that a run and its outbox row commit or roll back together; inserting the same occurrence twice creates one run and one outbox row; a dispatch payload never contains secret values (asserted by scanning the bytes for the test secret).

**Wiring:** services import `RunCreationConfiguration` (run factory, inserter, fire notifier, run creator; `kairos.engine.runs.dispatch-horizon`, default 5s, must match the fire poller's look-ahead), which imports `OutboxConfiguration` (cell transaction guard, outbox writer and the process's single `KairosIdFactory`) and through it `PersistenceConfiguration`. A service that only writes outbox rows imports `OutboxConfiguration` alone.

---

## 8. Phase 2: services

### 8.1 kairos-api

### T-201 API foundation
**Status:** done · **Needs:** T-107, T-109, T-111 · **Owns:** `dev.kairos.api.support.*`, `dev.kairos.api.config.*`, `dev.kairos.api.security.*`

- [x] `ProblemDetailsAdvice` (`@RestControllerAdvice`): maps `KairosException` subtypes, validation errors, malformed JSON, unknown fields, unsupported media type, missing headers and 404/405 into RFC 9457 bodies with the LLD codes. Unknown errors become a generic 500 with `traceId`, and the details are logged server-side. Unknown paths and unsupported methods both answer 404 `not-found`, and an unsupported or unacceptable media type answers 400 `validation`, so no status outside the LLD catalogue is used; an unreachable or timed-out database answers 503 `unavailable` with `Retry-After`. Field errors carry only the path and a stable code (`required`, `length`, `pattern`, `range`, `format`, `unknown-field`); client-chosen names that are not plain identifiers are reported as `body`. `ProblemErrorController` answers the container's `/error` dispatch the same way.
- [x] `RequestIdFilter`: `X-Request-Id` (accepts a valid incoming value of ≤ 64 characters from an allow-listed character set, otherwise generates one), stored in MDC and echoed. Generated ids are 32 random hex characters; the MDC key is `requestId`.
- [x] Spring Security: stateless; no sessions, form login, HTTP basic or CSRF (bearer tokens only, no cookies); a `BearerAuthenticationFilter` that accepts either a JWT (verified by T-109) or an API key (`kairos_` prefix), and builds `KairosPrincipal(userId or keyId, teamId nullable, sessionId)`. `KairosPrincipal` is sealed: `UserPrincipal(user, team, sessionId)` or `ApiKeyPrincipal(keyId, team)`. A present but invalid credential is 401 at once; a control-database failure during the check is 503 (fail closed). The public operations (signup, login, refresh, JWKS) skip the filter, so an expired token sent with a refresh does not cause a 401.
- [x] Session check: the JWT's `sid` must still be an active session and the user's current team must match the token's team, using a cache of at most 30s, so revoked sessions and removed members stop working within 30s. API key lookups are cached for at most 60s. The token's `sid` is the refresh-token family id (T-202 issues access tokens with `sid = familyId`); a session is active while its family has an `ACTIVE`, unexpired row, and a disabled user is refused too. Caches are Caffeine (`kairos.api.auth.*`, bounded to 30s and 60s). A key hashed under the previous pepper is re-hashed on use. `JdbcRefreshSessionStore` (the `RefreshSessionStore` that `TokenConfiguration` needs) is provided here, in `dev.kairos.api.security.infrastructure`, so T-202 only uses it.
- [x] `@UserTokenOnly` annotation: operations reserved for user tokens return `403 forbidden-for-api-key` when called with an API key. A controller parameter of type `UserPrincipal` has the same effect.
- [x] `TeamContext` argument resolver: injects the caller's `TeamId`, and throws `403 no-team` when the principal has no team. Applied to every team-scoped controller method. `PrincipalArgumentResolver` also supplies `KairosPrincipal` and `UserPrincipal` parameters; these parameter types are hidden from the OpenAPI document.
- [x] Security headers filter (section 4.8), with a separate CSP for `/swagger-ui/**`. Swagger UI is disabled when `KAIROS_SWAGGER_UI_ENABLED=false` (the production default in Helm). `Cache-Control: no-store` is the default; responses may override it (JWKS).
- [x] Rate limiting: Bucket4j with Lettuce (`kairos:rl:*`), with configurable limits for login (per IP and per account), signup (per IP), team creation and member addition (per user), and team API quotas (per team). Fail closed: if Redis is unavailable, return 503 with `Retry-After`. Client IP comes from `X-Forwarded-For` only when the request arrives from a trusted proxy CIDR. `RateLimiter.consume(RateLimit, subject)` is a plain-Java port (limits: `LOGIN_PER_IP`, `LOGIN_PER_ACCOUNT`, `SIGNUP_PER_IP`, `PASSWORD_CHANGE_PER_USER`, `TEAM_CREATE_PER_USER`, `MEMBER_ADD_PER_USER`, `ENDPOINT_TEST_PER_TEAM`, `TEAM_API`), configured under `kairos.api.rate-limits.*`; bucket keys are `kairos:rl:<limit>:<first 128 bits of SHA-256(subject)>`, so Redis holds no emails or IPs. The team quota is applied to every authenticated team request by `TeamQuotaFilter`. The API has its own Lettuce connection (same `kairos.redis.*` settings), opened on first use, and a `redis` readiness indicator. `ClientAddresses` walks `X-Forwarded-For` from the right (`kairos.api.trusted-proxies`, empty by default).
- [x] Idempotency: an `@Idempotent` annotation and interceptor backed by `api_idempotency` in the database that owns the resource. Same key and same body hash return the stored response; the same key with a different body returns `409 idempotency-key-reused`; a concurrent request with the same key waits briefly, then gets the stored response or 409. Records live for 24h. `@Idempotent(store = CONTROL | NAME_SHARD | PATH_ID_SHARD)`: the control database (cross-cell operations: batch create, bulk redrive), the cell of `ShardKey.of(team, body.name)` (job and workflow create) or the cell of the path `id` (run now). The hash is SHA-256 of method, path, query and body; only 2xx responses are stored (status, `Location`, `ETag` and the exact body text), any other outcome frees the key; an in-progress record older than 60s is taken over. The concurrent-wait 409 uses `idempotency-key-reused` with the detail "A request with this Idempotency-Key is still being processed. Retry later."
- [x] `ETag` and `If-Match` helpers (`"<version>"`), `412` on mismatch, and `428 if-match-required` when `If-Match` is missing on `PUT`. `@IfMatch long version` binds the header; `ETags.of`, `ETags.expectedVersion` and `ETags.requireVersion` are the helpers. Only one strong tag with a positive version is accepted.
- [x] `CursorCodec`: opaque base64url of the sort-key values plus an HMAC (`KAIROS_CURSOR_HMAC_KEY`); a tampered cursor returns 400. The MAC also covers the list name and the caller's team, so a cursor cannot be reused on another list or by another team; errors are `cursor`/`format`. `PageLimit.resolve` and `Page<T>` give the `limit` rule and response shape.
- [x] OpenAPI: an `OperationCustomizer` adding the common error responses and the `Idempotency-Key`/`If-Match` headers where annotated; problem detail schema components; an `openApiExport` Gradle task writing `build/openapi/kairos-v1.json`; and a test listing all 68 LLD operations and asserting each exists in the document. `CommonResponsesCustomizer` and `PublicOperationsCustomizer` (springdoc global customizers); `OpenApiDocumentTest` reads the 68 operations from the LLD, writes the document, and keeps a `NOT_YET_IMPLEMENTED` set (all 68 today): each feature task removes its numbers, after which a missing endpoint fails the build.

**Acceptance:** web-slice tests for each error mapping; a request without a token returns 401 with the problem body; a JWT for a user without a team returns 403 `no-team`; the rate-limit test returns 429 with `Retry-After`, and 503 with Redis stopped; idempotent replay returns the identical body.

### T-202 Accounts APIs (LLD APIs 1–7)
**Status:** done · **Needs:** T-201 · **Owns:** `dev.kairos.api.accounts`

- [x] 1 `POST /v1/auth/signup`: email normalised (lower-case, ≤ 254, simple structural check), password policy, Argon2id; `409 email-taken` for an existing account; rate-limited per IP. The new user has no team.
- [x] 2 `POST /v1/auth/login`: generic `invalid-credentials` error; progressive lockout (`failed_logins`, `locked_until`); constant-time path for unknown emails (hash a dummy password); creates a session family; returns the access and refresh tokens. Lockout (`kairos.api.accounts.lockout.*`): from 5 consecutive failures each failure locks for 1m doubling up to 1h; attempts while locked are refused without being counted. Every path spends one Argon2id verification (the real one even for a locked or disabled account). Rate limits per IP and per typed email come first; `kairos_login_failures_total` counts refusals.
- [x] 3 `POST /v1/auth/refresh`: rotation, reuse detection (revokes the family), picks up a new team id into the JWT.
- [x] 4 `POST /v1/auth/logout`: revokes the session; unknown or already revoked tokens also return 204.
- [x] 5 `GET /v1/me`: profile, including the team (nullable). `/v1/me` and `/v1/me/password` called with an API key answer 401 `unauthenticated` with the LLD's "A valid access token is required."
- [x] 6 `POST /v1/me/password`: verifies the current password (`400 invalid-current-password`), applies the policy, is rate-limited, and revokes all other sessions of the user.
- [x] 7 `GET /.well-known/jwks.json`: public keys with caching headers.
- [x] Audit entries for login failure bursts, password change and logout-all. Actions `LOGIN_LOCKED` (actor `system:login-lockout`), `PASSWORD_CHANGED`, and `SESSIONS_REVOKED` (other sessions revoked by a password change, or a family revoked by refresh-token reuse, actor `system:refresh-reuse`); resource type `user`. Written by the feature's own `AccountAudit` port on `audit_log`, in the same transaction as the change, until T-203d's `AuditWriter` exists.
- [x] Swagger annotations with examples copied from the LLD.

**Acceptance:** web and integration tests for every success and error row in LLD section 3.3; login timing for unknown and known emails is within 20% (benchmark test); refresh reuse revokes every token in the family.

### T-203a Team and members APIs (LLD APIs 8–12)
**Status:** done · **Needs:** T-201, T-202 (user repository) · **Owns:** `dev.kairos.api.team`

- [x] 8 `POST /v1/teams`: only for a user without a team (`409 already-in-team`). Creates the team with default quotas, makes the caller a member and creates the team's encryption and signing keysets, in one transaction on the control database. The `signingKey` is returned only once. `409 team-name-taken` on a slug conflict; rate-limited per user. The caller refreshes to get a token carrying the team.
- [x] 9 `GET /v1/team`.
- [x] 10 `GET /v1/team/members` with cursor pagination.
- [x] 11 `POST /v1/team/members`: adds an existing user (by email) who has no team; no emails are sent. Errors as in the LLD: `404 user-not-found`, `409 user-in-another-team`, `403 forbidden-for-api-key` (user tokens only), `429 rate-limited`. The added user gets the team on the next token refresh.
- [x] 12 `DELETE /v1/team/members/{userId}`: remove a member, or leave (own id); user tokens only (`403 forbidden-for-api-key`). A team must keep at least one member (`409 last-member`). The user's team is cleared and all their sessions are revoked, so existing access tokens stop working within 30s (session check in T-201).
- [x] Audit entries for team creation and membership changes.

**Acceptance:** tests for each LLD row; a removed member's access token is rejected within 30s, without waiting for it to expire.

### T-203b API key APIs (LLD APIs 13–15)
**Status:** done · **Needs:** T-201, T-109 · **Owns:** `dev.kairos.api.apikeys`

- [x] 13 `POST /v1/api-keys`: user tokens only (`403 forbidden-for-api-key`); returns the full key once; stores only the HMAC; optional expiry.
- [x] 14 `GET /v1/api-keys`: metadata only (`keyId`, name, created, last used, expiry).
- [x] 15 `DELETE /v1/api-keys/{keyId}`: user tokens only; revokes the key. Cached key lookups expire within 60s, after which the key is rejected everywhere.
- [x] `last_used_at` updated at most once per minute per key (no write per request).

**Acceptance:** a created key authenticates; a revoked key returns 401 within 60s; the stored hash never equals the secret; the list never shows the secret.

**Verification (2026-10-23):** Docker `spotlessApply build check` passed (2,067 tests; API line coverage 93.78%). Integration tests cover expiry/revocation with an injected clock, team-scoped authenticated cursors, concurrent revocation, throttled usage updates and atomic audit rollback. An isolated Compose stack became healthy and passed signup → login → team → refresh → key creation/authentication/listing/revocation; its test containers and volumes were removed. Implementation is on `t-203b-api-keys`, ready for PR review against `develop`. Gradle regenerated two missing BOM metadata checksums; dependency versions and lockfiles are unchanged.

### T-203c Secrets and CA bundle APIs (LLD APIs 16–22)
**Status:** done · **Needs:** T-201, T-109 · **Owns:** `dev.kairos.api.secrets`, `dev.kairos.api.cabundles`

**Integration scope:** API wiring/OpenAPI verification, credential-field rejection in the shared audit writer, and test-only certificate-generation dependencies with their generated lockfile.

- [x] 16 `POST /v1/secrets`: name, kind and value validated per kind; encrypted with the team's `SECRETS` keyset (keyset created on first use); version 1. Never returned.
- [x] 17 `GET /v1/secrets`: names, kinds, versions and dates only.
- [x] 18 `PUT /v1/secrets/{name}`: adds a new version; new calls use it within 60s (executor secret cache TTL).
- [x] 19 `DELETE /v1/secrets/{name}`: `409 secret-in-use` while a current or retained immutable endpoint version references it.
- [x] 20 `POST /v1/ca-bundles`: PEM parsed and validated (certificates only, CA flag set, not expired, ≤ 64 KB, at most 10 certificates); failures return the LLD `400` message; `409 ca-bundle-exists` for a duplicate name.
- [x] 21 `GET /v1/ca-bundles`, 22 `DELETE /v1/ca-bundles/{name}` (`409 ca-bundle-in-use` while referenced by a current or retained endpoint version).
- [x] Audit entries for every change (without values).

**Acceptance:** stored ciphertext decrypts only with the right associated data; no secret value appears in responses, logs or audit details (asserted with a canary value across the whole test).

**Verification (2026-10-23):** Docker `spotlessApply build check` passed with strict dependency verification: 2,108 tests, no failures/skips; API line coverage 94.89%. Integration tests cover row-bound decryption, all credential kinds, strict value types, redaction canaries, concurrent creation/rotation, current and retained endpoint references, team isolation, pagination and audit rollback. A fresh isolated Compose stack became healthy and passed API-key secret create/rotate/list/delete, generated-CA create/list/delete, duplicate/invalid-input handling, and audit redaction; its test containers and volumes were removed. No schema migration or production dependency changes; certificate fixtures are generated in memory using existing pinned Bouncy Castle versions as test dependencies.

### T-203d Signing key and audit log APIs (LLD APIs 23–24)
**Status:** done · **Needs:** T-201, T-109 · **Owns:** `dev.kairos.api.signing`, `dev.kairos.api.audit`

**Integration scope:** existing account/team/API-key audit adapters and wiring, the security library's key-pruning method, and a new signing-overlap migration with its schema fixtures.

- [x] 23 `POST /v1/team/signing-key:rotate`: user tokens only. Requests are signed with the new key at once; the response carries the new key and `previousKeyValidUntil`, until which targets should accept both keys.
- [x] 24 `GET /v1/audit-log`: time range (default last 24h, at most 90 days back), action filter, cursor pagination; team-scoped.
- [x] An `AuditWriter` component (used by every API task) that writes `audit_log` rows in the same transaction as the change. It is merged first, as "interfaces first", so other API tasks can use it early.

- [x] Persist the previous-key deadline; prune expired non-primary keys in bounded, replica-safe batches. Rotation and pruning use optimistic replacement, with keyset encryption outside transactions.

**Acceptance:** rotation keeps old signatures verifiable during the overlap; audit queries never return another team's entries.

**Verification (2026-10-23):** Docker `spotlessApply build check` passed: 2,088 tests, no failures/skips, API line coverage 94.24%. Tests cover signature verification, concurrent rotation, expiry after configuration changes, stale cleanup, audit rollback, team isolation, cursor/filter binding, time bounds and secret redaction. A fresh isolated Compose stack applied the migration, became healthy, and passed onboarding, API-key authentication, two signing rotations, API-key refusal on rotation, and audit filtering/pagination. Test containers and volumes were removed. No dependency changes.

### T-204 Endpoint APIs (LLD APIs 25–33)
**Status:** done (Abhishek Khot) · **Needs:** T-201, T-110, T-203c (secret lookup) · **Owns:** `dev.kairos.api.endpoints`

- [x] `EndpointSpec` record and validation (LLD section 3.5): method, safe `urlTemplate` with path-only parameters, managed-header refusal, typed secret and CA references, timeout, status rules, retry policy, limits, output selectors, unknown-outcome and suspend policies. Serialized spec size is capped at 64 KB.
- [x] APIs 25–29: create writes immutable version 1, list uses status-bound signed cursors, get is team-scoped, update requires `If-Match` and appends a version, and delete checks job references in every cell.
- [x] API 30 calls only the T-243 internal executor endpoint; the API signs a team-claim token for `kairos-executor-internal` with a 30-second lifetime, checks missing path parameters, applies the shared 10/minute team quota, relays redacted output, and stores no test result.
- [x] APIs 31–32 commit endpoint status and audit in control first, then reconcile schedules in every cell. The watcher reconciles held schedules for both suspended and resumed endpoints, including after a partial cell failure; state processing applies each job's misfire policy.
- [x] API 33 aggregates `endpoint_health_minute` over 5m–24h and calculates p95 from cell run history. The breaker field is `UNKNOWN` because executor breaker state is process-local.
- [x] Audit entries cover create, update, delete, suspend and resume.
- [x] Secret and CA reference validation uses T-203c metadata lookups in the mutation transaction after locking the team row `FOR UPDATE`.

**Acceptance:** Docker integration tests cover CRUD and immutable versions, If-Match, team isolation, safe-target/reference refusal, endpoint-test relay and rate limit, use conflicts, health windows, and cross-cell suspend/resume. Resume releases held schedules so the state processor can apply the saved job misfire policy.

**Verification (2026-10-24):** Docker detached `make bg BG_NAME=kairos-gradle-t204-final ARGS='spotlessApply build check'` passed. The API suite reports 354 tests and the watcher suite 119 tests, with no failures, errors or skips. `git diff --check` is clean. The API now explicitly depends on `kairos-http`; its dependency lock was refreshed. T-204 was merged to `develop` through PR #39 on 2026-10-24.

### T-205 Job APIs (LLD APIs 34–45)
**Status:** in progress (Abhishek Khot) · **Needs:** T-201, T-102, T-103, T-113, T-204 (endpoint lookup) · **Owns:** `dev.kairos.api.jobs`

- [ ] `JobSpec` record and validation (LLD section 3.6): schedule (`IMMEDIATE`, `ONE_TIME`, `CRON`, `INTERVAL`), request values for the endpoint's parameters, priority, misfire policy, retry override, `autoPauseAfter`, `maxLatenessMs`. Size ≤ 128 KB.
- [ ] 34 create (`Idempotency-Key`): the shard is computed from team and name; writes `jobs`, `job_versions` and `schedules` (or, for `IMMEDIATE`, a run and its outbox row through T-113) in one cell transaction; enforces the team's `maxJobs` quota; returns `nextFireAt`.
- [ ] 35 batch create (at most 1K jobs per request; `429 quota-exceeded` when the batch would pass the team's job limit): grouped by cell and committed per cell, so one item's failure does not roll back the others; per-item results as the LLD shows.
- [ ] 36 list (status, endpoint, name prefix, cursor; fan-out across cells), 37 get.
- [ ] 38 update (`If-Match`; new version; the schedule is recomputed; queued runs keep their pinned version).
- [ ] 39 delete: in one transaction, the schedule is removed, waiting, queued and delayed runs are cancelled, running runs get a cancel outbox row, and the status becomes `DELETED`; the name becomes reusable.
- [ ] 40 pause (only `ACTIVE`; runs already queued still execute) and 41 resume (only `PAUSED` or `AUTO_PAUSED`; the next fire time is computed and fires missed while paused follow the misfire policy).
- [ ] 42 cancel: same as delete, but the job stays visible with status `CANCELLED` (`409 invalid-state` if already cancelled).
- [ ] 43 run now (manual trigger; `Idempotency-Key`), 44 backfill (CRON and INTERVAL jobs only, `409 not-recurring` otherwise; at most 10K occurrences, `400 range-too-large`; occurrences that already ran are skipped; inserted as `DELAYED` runs released at 100/s per job).
- [ ] 45 list a job's runs (state filter, time range within 7 days, combining `run_inflight` and `run_history`).

**Acceptance:** tests for every LLD row; creating the same job concurrently with one `Idempotency-Key` creates one job; deleting a job with a running run produces exactly one cancel message; the quota returns 429 `quota-exceeded`.

### T-206 Workflow and workflow-run APIs (LLD APIs 46–58)
**Status:** todo · **Needs:** T-201, T-104, T-113, T-204 · **Owns:** `dev.kairos.api.workflows`, `dev.kairos.api.workflowruns`

- [ ] Workflow spec validation through T-104, plus endpoint existence for every task, and the team quota `maxWorkflowTasks`. Size ≤ 512 KB. The computed plan is stored with the version.
- [ ] 46 create, 47 list, 48 get, 49 update (`If-Match`), 50 delete, 51 pause, 52 resume, 53 cancel, 54 run now, 55 list a workflow's runs.
- [ ] Run now and schedule-driven creation share one path: a `workflow_runs` row plus root task runs and their outbox rows, in one cell transaction (through T-113).
- [ ] 56 get a workflow run (task states, counters, outputs with sensitive values redacted).
- [ ] 57 cancel a workflow run (cancels pending tasks and sends cancel messages for running tasks).
- [ ] 58 retry a workflow run (only `FAILED` or `BLOCKED`, otherwise `409 invalid-state`): failed and skipped tasks are re-queued with new attempt series, and outputs of tasks that succeeded are reused.

**Acceptance:** tests for every LLD row; a cyclic workflow returns the LLD error with the cycle path; run now on a diamond workflow creates only the root task runs.

### T-207 Run, DLQ and stats APIs (LLD APIs 59–68)
**Status:** done · **Needs:** T-201, T-113 · **Owns:** `dev.kairos.api.runs`, `dev.kairos.api.dlq`, `dev.kairos.api.stats`

- [x] 59 list runs (filters, fan-out across cells, cursor), 60 get a run (current state plus attempts from `run_history`), 61 cancel a run (waiting, queued and delayed runs are deleted with their unpublished outbox row; a running run is flagged and gets a cancel message).
- [x] 62 list DLQ entries, 63 get, 64 acknowledge (assignee, notes), and 66 discard.
- [x] 65 redrive (`ORIGINAL` or `CURRENT` configuration; the run starts a new attempt series under the same run id, so the target sees the same `Idempotency-Key`, and is re-created through `RunFactory.redrive` from the entry's stored `fence`; a blocked workflow run resumes; `409 dlq-resolved`, `409 endpoint-suspended`).
- [x] 67 bulk redrive (at least one filter required; `dryRun` returns only the count; `409 endpoint-suspended` until the endpoint is resumed; matching entries become `DELAYED` runs released at 100/s).
- [x] 68 team stats from `team_stats_minute` and the current DLQ count.

**Acceptance:** tests for every LLD row; redriving one entry twice starts one new attempt series and the second call returns `409 dlq-resolved`; bulk redrive respects pacing.

**Verification:** PR #38 is merged into `develop`. Docker `make bg BG_NAME=kairos-gradle-t207-repo-final ARGS='spotlessApply build check'` passed on 2026-10-24. The API check reports 341 tests with no failures, errors or skips and 92.2% line coverage. The run list test fans out across two cells; API acceptance covers DLQ redrive idempotence, bulk dry-run replay, stats percentiles and the OpenAPI inventory. Existing T-223 Redis integration tests verify the delayed poller's 100/s redrive pacing cap.

### 8.2 kairos-watcher

All watcher components live in `services/kairos-watcher` under `dev.kairos.watcher.<feature>`. They build on the `ShardedPoller` template from T-221.

### T-221 Shard lease manager and node registry
**Status:** done · **Needs:** T-107, T-113 · **Owns:** `dev.kairos.watcher.lease`, `dev.kairos.watcher.node`, `dev.kairos.watcher.config`, `dev.kairos.watcher.support.ShardedPoller`, watcher build/runtime configuration and tests

- [x] Node registry: insert or update `scheduler_nodes` every 5s; node id `{service}-{pod}-{startMillis}`; clean up stale rows.
- [x] Lease manager every 5s per cell: renew owned leases (TTL 15s) with `generation` checks; acquire free or expired leases up to the fair share `ceil(cell shard count / live watchers)`; release the surplus above fair share + 2, oldest first.
- [x] `OwnedShards` view exposed to pollers. Losing a lease stops work on that shard before the lease expires (a local deadline at TTL − 3s).
- [x] `ShardedPoller` base: runs a callback per owned shard group on a schedule with jitter, measures duration, stops cleanly on shutdown, and checks lease validity before committing.
- [x] On shutdown: release leases so another watcher takes over immediately.

**Acceptance:** with 2 watchers, shards split about 128/128 within 30s; killing one moves all its shards within 20s; the generation check prevents a paused watcher from renewing a lease it lost.

**Verification:** Docker `make bg BG_NAME=kairos-gradle-t221-final ARGS="spotlessApply build check"` passed: 2,144 tests, no failures/errors/skips; watcher line coverage 88.82%. PostgreSQL integration tests cover two-watcher sharing, release and takeover generation, stale-owner fencing, and poller work inside a lease-guarded transaction. No migrations or production dependency versions changed; Testcontainers is test-only.

### T-222 Fire poller
**Status:** done · **Needs:** T-221, T-102, T-113 · **Owns:** `dev.kairos.watcher.fire`

- [x] Every 1s (±20%) per group of 8 owned shards, and on `NOTIFY`: select due schedules (`next_fire_at <= now + 5s look-ahead`, status active, ≤ 250 per shard) with `FOR UPDATE SKIP LOCKED`.
- [x] For each schedule: compute occurrences with the misfire policy (60s threshold), create runs or workflow runs plus root tasks and outbox rows through T-113, and advance `next_fire_at` and `last_fired_local`. One-time schedules are deleted after firing. All of this happens in one cell transaction per batch.
- [x] Back-pressure: skip claiming when the oldest due outbox row in the cell is more than 10s old (Kafka or relay trouble), and expose a metric.
- [x] Held schedules (endpoint suspended) and paused ones are never selected.
- [x] Metrics: fires per second, poll duration, schedules per batch, misfires by policy.

**Acceptance:** 10K cron schedules due in the same minute fire once each (unique constraint plus a test count); with two watchers and a forced lease handover mid-run, no occurrence is created twice or skipped.

**Verification:** Docker `make bg BG_NAME=kairos-gradle-t222-final2 ARGS="spotlessApply build check"` passed: 2,153 tests, no failures, errors or skips. Watcher PostgreSQL integration tests cover 10K cron schedules, one-time and workflow materialization, transactional rollback, held schedules, outbox back-pressure, concurrent claims, committed notifications and a forced lease handover that creates one occurrence. Both watchers and all other services became healthy in an isolated detached Compose stack.

### T-223 Delayed poller
**Status:** done · **Needs:** T-221, T-113 · **Owns:** `dev.kairos.watcher.delayed`

- [x] Every 1s per shard group: select `DELAYED` runs with `due_at <= now` (≤ 250 per shard), set them to `QUEUED`, increment the fence and write dispatch outbox rows, in one transaction.
- [x] Pacing: backfill at 100/s per job and 1K/s overall, redrive at 100/s, team `maxExecPerSec` deferrals; shared Redis counters enforce each second's limits and excess runs are spaced through durable `due_at`, so no runs are held in watcher memory.
- [x] Endpoint suspended: runs stay `DELAYED` with `due_at` pushed by the suspension check interval and `deferrals` incremented.

**Acceptance:** retries become due within 1s of their `due_at`; a backfill of 10K occurrences proceeds at the configured rate.

**Verification:** Docker `make bg BG_NAME=kairos-gradle-t223-final ARGS='spotlessApply build check'` passed: 2,160 tests, no failures, errors or skips. PostgreSQL integration tests cover due retry fencing and outbox atomicity, suspension deferral, transaction rollback, pinned-version loading and 10K backfill at 100/s. Redis integration tests verify team, per-job backfill, global backfill and redrive caps shared across watcher connections. Both watcher replicas became healthy in an isolated detached Compose stack; the stack and volumes were removed.

### T-224 Outbox relay
**Status:** done · **Needs:** T-221, T-108 · **Owns:** `dev.kairos.watcher.outbox`

- [x] Every 100 ms per owned shard group: read up to 2K due rows (`available_at <= now`) in `outbox_id` order, publish them to their topics with keys, wait for all acknowledgements, then delete the acknowledged rows in batches of ≤ 2K. A crash before the delete republishes, which the execution gates make harmless.
- [x] Rows that keep failing are retried with backoff. A row is never dropped, and its age feeds the back-pressure metric.
- [x] Optional global dispatch cap per second (token bucket per watcher, configurable).
- [x] Metrics: published per topic, batch latency, oldest due row age per cell (`kairos_outbox_oldest_due_seconds`), backlog size.

**Acceptance:** with Kafka paused for 60s and then resumed, every row is published at least once and the outbox drains; no row is deleted before its acknowledgement (fault-injection test).

**Verification:** Docker `make bg BG_NAME=kairos-gradle-t224-final ARGS='spotlessApply build check'` passed: 2,171 tests, no failures, errors or skips. PostgreSQL fault tests prove rows remain until individual Kafka acknowledgements, failures are deferred while acknowledged rows delete, a rollback retains acknowledged rows for replay, and the optional cap leaves unsent rows due. Tests cover the 2K claim limit, due ordering, 30s maximum backoff, gauges and real Kafka delivery. In an isolated detached Compose stack, Kafka was paused for over 60s after 100 rows were inserted; all 100 remained during the pause, then the outbox drained after resume and Kafka showed 193 records, including expected at-least-once replays. Both watcher replicas returned healthy; the stack and volumes were removed.

### T-225 Recovery: lease sweeper, stuck-queued sweeper, Redis failover monitor
**Status:** done · **Needs:** T-221, T-111, T-113, T-103 · **Owns:** `dev.kairos.watcher.recovery`

- [x] Lease sweeper every 5s: `RUNNING` runs with `lease_until + 30s < now`. If the Redis lock is still held, skip. Otherwise check the sent marker: no marker means re-queue with `infra_attempt + 1` (max 3, then dead-letter as `EXECUTOR_LOST`). A marker (or the failover quarantine) means the unknown outcome is decided by `unknownOutcomePolicy`, recorded as such in history.
- [x] Stuck-queued sweeper every minute: `QUEUED` for more than 10 min gets a new fence and a re-dispatch outbox row, plus an alert metric.
- [x] Redis failover monitor every 2s: detects a change of master replication id. On change, set `scheduler_flags.redis_marker_quarantine_until = now + 310s`; while quarantined, a missing marker counts as an unknown outcome.

**Acceptance:** killing an executor mid-call with no marker re-queues the run once; with a marker, a `REVIEW` endpoint dead-letters it and a default endpoint retries with the same idempotency key; a simulated Redis failover sets the quarantine flag.

**Verification:** Docker `make bg BG_NAME=t225-final2 ARGS='spotlessApply build check'` passed: 2,181 tests, no failures, errors or skips. PostgreSQL/Redis integration tests cover absent and present markers, `REVIEW` and default retry policy, maximum-age expiry, three-infrastructure-attempt exhaustion, a held lock, quarantine, a stuck queued redispatch, and atomic rollback. A simulated primary replication-id change writes the 310-second flag. Both watcher replicas and the full isolated detached Compose stack became healthy; watcher logs had no errors, and the stack and volumes were removed.

### T-226 Singleton tasks: auto-suspend, review timeout, purger
**Status:** done · **Needs:** T-221, T-103 · **Owns:** `dev.kairos.watcher.singleton`

- [x] Single-instance execution through a Postgres advisory lock on the control database (`pg_try_advisory_lock`), held on a dedicated connection and released on shutdown.
- [x] Auto-suspend evaluator every 15s: applies the T-103 rule over `endpoint_health_minute`; suspends through the same path as API 31; writes an audit entry (`system:auto-suspend`); respects the opt-out and the cool-down.
- [x] Review-timeout sweeper every 60s: `BLOCKED` workflow runs past `blocked_until` (24h) fail their dependents as `UPSTREAM_FAILED`.
- [x] Purger every hour: resolved DLQ entries and `DELETED` definitions older than 7 days, idempotency records older than 24h, expired sessions. Deletes in batches of ≤ 2K with a pause between batches.

**Acceptance:** two watchers never run the same singleton at the same time (test with forced contention); an endpoint over the threshold is suspended once and its schedules are held.

**Verification:** Docker `make bg BG_NAME=t226-final2 ARGS='spotlessApply build check'` passed: 2,185 tests, no failures, errors or skips. PostgreSQL integration tests cover advisory-lock contention and release, threshold suspension with audit and schedule hold, opt-out, cool-down, five-minute window boundary, dependent-task timeout propagation, and retention cleanup across a 2,000-row batch boundary. Both watcher replicas and all Compose services became healthy; watcher logs had no errors, and the isolated stack, volumes, and generated credentials were removed.

### 8.3 kairos-executor

### T-241 Dispatch consumers and back-pressure
**Status:** done · **Needs:** T-108, T-113 · **Owns:** `dev.kairos.executor.consume`

- [x] Three consumers (`kairos.run.high`, `.medium`, `.low`) with poll sizes 250, 150 and 100, giving the high tier a larger share.
- [x] Each record is handed to a virtual thread. In-flight runs are tracked per pod; consumption pauses at 4K in flight and resumes at 3K (LLD section 4.1).
- [x] Offsets are committed only for records whose processing finished (claim rejected, result published, or deferred). A per-partition tracker commits the highest contiguous finished offset.
- [x] Per-team in-flight quota (`maxInflight`) and per-endpoint `maxConcurrency`. Over-limit records are deferred back through the database (state `DELAYED` with a short `due_at`), never dropped and never spun on.
- [x] Metrics: in-flight per tier, consume lag, records per poll, deferrals.

**Acceptance:** under a flood larger than capacity, in-flight never exceeds 4K and memory stays flat; a restart re-delivers only unfinished records.

### T-242 Execution gates
**Status:** done · **Needs:** T-241, T-111, T-107, T-110 (`SendObserver`) · **Owns:** `dev.kairos.executor.gate`

This task implements the guarantee that no run executes twice. Review it with extra care.

- [x] Gate 1, Redis run lock: acquire `kairos:lock:run:{runId}` with TTL = endpoint timeout + 10s. If not acquired, drop the message (another executor owns the run). If Redis is unavailable, stop consuming (fail closed) and resume when Redis is back.
- [x] Gate 2, fenced claim: `UPDATE run_inflight SET state = RUNNING, fence = fence + 1, owner_node, lease_until WHERE run_id = ? AND fence = ? AND state = QUEUED AND NOT cancel_requested RETURNING ...`. Zero rows means stale, duplicate or cancelled: release the lock and drop the message.
- [x] Gate 3, send-time guard: immediately before the request is written (through `SendObserver`), check `remaining(lock) >= timeout + 2s`. Otherwise abort before sending and re-queue (not counted as an attempt).
- [x] Gate 4, sent marker: write `kairos:sent:{runId}:{attempt}:{infraAttempt}` (48h) immediately before the bytes leave. If the marker cannot be written, do not send.
- [x] Lease renewal for long calls (extend the lock and `lease_until` together at half the TTL).
- [x] Gates are ordered `ExecutionGate` steps (chain of responsibility) with one metric per gate outcome.
- [x] Release the lock after the result is published (not before).

**Acceptance:** a test that delivers the same dispatch message to two executors concurrently 1K times records exactly one call per run on the mock target; a paused executor (simulated GC pause past the TTL) does not send; a cancelled run is never sent.

### T-243 Render, call, classify and publish result
**Status:** done · **Needs:** T-242, T-110, T-109, T-103, T-104 · **Owns:** `dev.kairos.executor.call`

- [x] Load the pinned endpoint version (Caffeine cache keyed by team, id and version; versions are immutable, so the cache never goes stale).
- [x] Render the request from the dispatch snapshot: path parameters, query, headers, body; upstream outputs for workflow tasks (sensitive outputs decrypted only in memory); `Idempotency-Key: <runId>`; `traceparent`; `X-Kairos-Run-Id`; `X-Kairos-Attempt`; `X-Kairos-Signature` and timestamp.
- [x] Apply auth (secret resolved and decrypted for the call) and the team CA bundle.
- [x] Circuit breaker per endpoint (Resilience4j, 50% failure rate over 100 calls, open 30s). An open breaker defers the run without counting an attempt. A per-endpoint `ratePerSec` limiter likewise defers.
- [x] Call, then classify with T-103, extract outputs with T-104 (sensitive ones sealed with the team's `OUTPUTS` keyset), redact the error and snippet, and publish a `result` message to `kairos.run.result` (key = the dispatch's target id, since the `Result` message carries no target id) with `acks=all`. On publish failure, retry; the run stays `RUNNING` with its lease, so recovery handles a crash.
- [x] Internal endpoint test API for LLD API 30: `POST /internal/v1/endpoint-tests` on the executor, authenticated with a short-lived service token signed by the platform keyset. It runs one call through the same render and call path, without run gates, and returns the redacted result. Local Compose keeps the executor on the internal backend network with no published port. T-204 applies the public shared team limit; T-401 adds the Helm NetworkPolicy.
- [x] Metrics: calls by outcome and error class, latency histogram per tier, breaker state changes.

**Acceptance:** against the mock target: 2xx succeeds with outputs extracted; 500 is retryable; 409 with an override counts as success; a timeout after send is an unknown outcome; an open breaker defers; the result payload never contains secrets or sensitive outputs in clear text.

**Verification:** Docker `make bg BG_NAME=kairos-gradle-t243-final-check ARGS='check'` passed. The executor suite reports 98 tests with no failures, errors or skips; executor line coverage is 70.65%. T-243 tests cover rendered URL encoding and signatures, success/output extraction and sealing, HTTP 500 and 409 classification, sent timeout uncertainty, send-veto deferral, retry-until-ack publication, breaker/rate-limit behavior, internal token validation, endpoint-test rate limiting and sensitive-output redaction.

### T-244 Cancel listener and graceful drain
**Status:** done · **Needs:** T-241, T-243 · **Owns:** `dev.kairos.executor.cancel`, `dev.kairos.executor.lifecycle`, executor dispatch/call lifecycle integration seams, cancellable-call API in `libs/kairos-http`

- [x] Cancel listener on `kairos.run.cancel` with a unique consumer group per pod (every executor sees every cancel). It aborts the matching in-flight call and publishes a `CANCELLED` result.
- [x] Graceful drain on SIGTERM: stop polling, let in-flight calls finish for up to 60s, re-queue unsent runs (release the lock, return them to `QUEUED` with a new fence and an outbox row), then commit offsets and exit. Readiness turns down at the start of the drain.

**Acceptance:** cancelling a run during a 10s mock call aborts it within 1s; a rolling restart under load loses no run and creates no duplicate call.

**Verification:** Docker `make bg BG_NAME=kairos-gradle-t244-final ARGS='spotlessApply :services:kairos-executor:check :libs:kairos-http:check'` passed. Executor: 103 tests, no failures/errors/skips, 73.29% line coverage. HTTP: 181 tests, no failures/errors/skips, 94.75% line coverage. Kafka integration verifies two pod groups receive the same cancel; a TLS-backed slow call is aborted within 1s; PostgreSQL verifies the drain requeue increments the fence and writes one outbox dispatch atomically; existing Kafka restart replay checks verify the uncommitted record is replayed.

### 8.4 kairos-state

### T-261 Result settler
**Status:** todo · **Needs:** T-108, T-113, T-103 · **Owns:** `dev.kairos.state.settle`

- [ ] Consume `kairos.run.result` in batches (≤ 1K). Group by cell and settle each cell's group in one transaction; commit offsets after all cells commit.
- [ ] Fence check: ignore a result whose fence does not match the row (late or duplicate result).
- [ ] Success: append to `run_history` (final), delete from `run_inflight`, update `job_health`, and write a `completed` outbox row when the team opted in.
- [ ] Retryable failure within policy (attempts and max age): append history (`FAILED_RETRYING`) and set the run `DELAYED` with `due_at = now + backoff`, `attempt + 1` and a new fence.
- [ ] Exhausted or non-retryable: append history (`DEAD_LETTERED`, final), insert `dead_letters` with the run's current `fence` (a redrive continues from it), write a `dead` outbox row, and update `job_health` (auto-pause the job after N consecutive dead runs; audit `system:auto-pause`).
- [ ] Unknown outcome: apply `unknownOutcomePolicy`.
- [ ] Cancelled: history `CANCELLED`, delete from inflight.
- [ ] Workflow task results are handed to T-262 inside the same transaction (an interface agreed first).
- [ ] One-time jobs move to `COMPLETED` or `FAILED` after their final run.

**Acceptance:** replaying the same batch twice changes nothing the second time; a run that fails 6 times lands in the DLQ after about 15 minutes of simulated time (with the injected clock); three consecutive dead runs auto-pause the job.

### T-262 Workflow progression
**Status:** todo · **Needs:** T-261 (interface), T-104, T-113, T-109 · **Owns:** `dev.kairos.state.workflow`

- [ ] On task success: store `task_outputs` (non-sensitive values as JSON, sensitive values sealed), decrement `remaining_deps` of downstream tasks, and for each downstream task reaching 0, set it `QUEUED` with the rendered snapshot and a dispatch outbox row.
- [ ] On task failure (final): mark descendants `UPSTREAM_FAILED` under `FAIL_FAST`, or set the workflow run `BLOCKED` with `blocked_until = now + 24h` under `BLOCK_FOR_REVIEW`.
- [ ] Update workflow run counters and status (`SUCCEEDED` when all tasks have succeeded or been skipped, `FAILED` when any task failed finally, `CANCELLED`).
- [ ] Retry of a workflow run (API 58) re-enters through the same component.

**Acceptance:** a diamond workflow runs B and C in parallel and D only after both; the output of A reaches D's header; a failing B blocks D under the default policy, and fails it fast under `FAIL_FAST`.

### T-263 Stats aggregator
**Status:** todo · **Needs:** T-261 · **Owns:** `dev.kairos.state.stats`

- [ ] In-memory counters per endpoint and team per minute from settled results, flushed every 10s with an upsert into `endpoint_health_minute` and `team_stats_minute` keyed by node id (no cross-node contention).
- [ ] Flush on shutdown; bounded memory (evict minutes older than 2).

**Acceptance:** totals in the tables match the number of settled results across two state replicas.

### 8.5 kairos-maintenance

### T-281 Partition manager
**Status:** todo · **Needs:** T-106, T-107 · **Owns:** `dev.kairos.maintenance.partitions`

- [ ] Daily run (a CronJob in Helm, a `make` target locally): create daily partitions 3 days ahead for `run_history`, `task_outputs` and `audit_log`; drop `run_history` and `task_outputs` partitions older than 7 days and `audit_log` partitions older than 90 days.
- [ ] Partition names are derived from dates only (no user input). DDL statements are built from that fixed format and validated against a strict pattern before execution.
- [ ] Idempotent and safe to run concurrently (advisory lock).

**Acceptance:** running it on day N creates N+1..N+3, and drops partitions older than the retention, in every cell.

### T-282 Operator commands
**Status:** todo · **Needs:** T-107, T-109 · **Owns:** `dev.kairos.maintenance.commands`

- [ ] picocli commands (Command pattern), run as `docker compose run --rm kairos-maintenance <command>` locally and as a Kubernetes Job in production:
  - `team set-quota --team <slug> --max-jobs ... --max-exec-per-sec ...`;
  - `user disable --email ...` (also revokes sessions);
  - `user reset-password --email ...` (generates a random temporary password, shows it once on the terminal, revokes sessions).
- [ ] Every command validates its arguments, writes an audit entry (`operator:<command>`) and prints a short result. No secrets are written to logs.
- [ ] Add a `kairos-maintenance` service to compose under a `tools` profile.

**Acceptance:** each command works against the running stack and leaves an audit entry.

---

## 9. Phase 3: verification and benchmarks

### T-301 Mock target
**Status:** todo · **Needs:** T-002 · **Owns:** `tools/mock-target/**`, its compose block

- [ ] HTTPS on port 8443 with a certificate issued at startup by an in-memory development CA. The CA certificate (public part only) is served on the management port (`/ca.pem`), so bench-cli can register it as a team CA bundle and the executor keeps full certificate verification. This development CA exists only in this tool.
- [ ] Behaviour controlled per request through path or query (`/behave?status=500&latencyMs=200&failRate=0.1&bodyBytes=1024`) and through named profiles for benchmarks.
- [ ] Duplicate detector: records every `Idempotency-Key` with a count (in memory plus periodic snapshot), and exposes `/stats` (total calls, unique keys, keys seen more than once) on the management port.
- [ ] Verifies `X-Kairos-Signature` when a verification key is configured, and counts failures.
- [ ] Sized for 50K requests/s across replicas (Tomcat with virtual threads, no per-request logging).
- [ ] Compose: add `KAIROS_TARGET_POLICY_ALLOWED_HOSTS=mock-target` and port 8443 to the executor and API environments for local use only.

**Acceptance:** a test call from the executor to `https://mock-target:8443` succeeds with verification on; `/stats` reports duplicates correctly in a unit test.

### T-302 Benchmark CLI
**Status:** todo · **Needs:** T-205, T-206, T-301 · **Owns:** `tools/bench-cli/**`

- [ ] picocli commands: `seed` (signs up a user, creates a team, an API key, the CA bundle, endpoints pointing at the mock target, and N jobs with a chosen schedule mix through the batch API), `load` (drives a target executions-per-second profile using immediate jobs, run now and cron fan-out), `verify` (compares created occurrences with mock-target `/stats` and `run_history`) and `report`.
- [ ] Profiles: `steady-20k` (20K executions/s for 30 minutes), `ramp-50k` (ramp from 20K to 50K over 10 minutes, hold for 10), `burst` (top-of-minute spike of 50K cron fires) and `smoke` (500/s for 2 minutes for local runs).
- [ ] Metrics reported: achieved executions/s, schedule lag p50/p95/p99 (from `run_history.started_at − scheduled_at`), outcome mix, duplicates (must be 0), missing runs (must be 0), and DLQ count.
- [ ] Output: a console summary plus a JSON and Markdown report under `build/bench/`.
- [ ] Runs as a container: `docker compose --profile bench run --rm bench-cli load --profile smoke`. A laptop runs `smoke`; `steady-20k` and `ramp-50k` run on GKE (T-306).

**Acceptance:** `smoke` passes locally with 0 duplicates, 0 missing runs and lag p99 ≤ 2s.

### T-303 Cross-module integration suites
**Status:** todo · **Needs:** the services in phase 2 · **Owns:** `services/*/src/integrationTest/**` (a new source set added by this task in build-logic)

- [ ] An `integrationTest` source set and Gradle task in the service conventions, run by `make test`.
- [ ] Per service: Testcontainers with the real migrations applied, the service started with `@SpringBootTest`, and its flows exercised against real Postgres, Kafka and Redis (API: create a job and observe the outbox row; watcher: fire and publish; executor: consume and call the mock target; state: settle).

**Acceptance:** suites run in under 10 minutes in a detached build (`make bg ARGS=integrationTest`).

### T-304 End-to-end scenarios
**Status:** todo · **Needs:** T-302 · **Owns:** `tools/bench-cli/src/main/java/dev/kairos/bench/scenario/**`

Scenarios run with `bench-cli scenario <name>` against the detached stack (`make up`):

- [ ] `onboarding`: signup, login, create a team, add a second user as a member, create an API key, then use the API key.
- [ ] `job-lifecycle`: register an endpoint, create cron, one-time, interval and immediate jobs, observe runs succeed, pause, resume, update, delete.
- [ ] `retry-to-dlq`: an endpoint that always fails, so the run reaches the DLQ after 5 retries (with a short test retry policy), then acknowledge, redrive (succeeds after the mock target is switched to healthy) and discard.
- [ ] `workflow-data-passing`: a diamond workflow where task A's output is injected into D's header and body; verified on the mock target.
- [ ] `isolation`: team B gets 404 for every id belonging to team A across all 68 APIs.
- [ ] `auto-protection`: three consecutive dead runs auto-pause a job, and a failing endpoint is auto-suspended.

**Acceptance:** all scenarios pass in sequence against a fresh `make up`, and the run is documented in the README.

### T-305 Resilience and duplicate-execution proof
**Status:** todo · **Needs:** T-302, T-304 · **Owns:** `scripts/chaos/**`, `tools/bench-cli` scenario `chaos`

Each experiment runs during `load --profile smoke`, then checks 0 duplicates (mock-target `/stats`) and 0 missing runs (`verify`).

- [ ] Kill an executor (`docker kill`) mid-load, repeated 5 times.
- [ ] Kill a watcher during lease handover.
- [ ] Pause Kafka for 60s (`docker pause`), then resume; the outbox backs up and drains.
- [ ] Restart Redis (executors pause and resume); then simulate a failover to check that quarantine handles missing markers.
- [ ] Pause a cell database for 30s; only that cell's shards stall.
- [ ] Slow target (5s latency) at full load; the breaker and back-pressure hold.
- [ ] Rolling restart of every service under load.

**Acceptance:** a report listing each experiment with duplicates = 0 and missing = 0, and recovery times within the HLD targets.

### T-306 Per-cell write test and tuning
**Status:** todo · **Needs:** T-305, T-401, T-402 · **Owns:** `perf/**`, tuning changes in the owning modules (agreed per change)

- [ ] On GKE with Cloud SQL sized as in the HLD: run `steady-20k`, then `ramp-50k`.
- [ ] Measure per-cell write throughput, WAL volume, autovacuum behaviour on `outbox` and `run_inflight`, Kafka lag and executor saturation.
- [ ] Decide between 2 cells and 4 cells of 64 shards based on per-cell headroom at 50K/s (target ≤ 60% of the measured cell ceiling), and record the decision and evidence in the HLD.
- [ ] Tune pool sizes, batch sizes, poll intervals and partition counts; each change goes in a small pull request with before and after numbers.

**Acceptance:** 20K/s sustained for 30 minutes and 50K/s peak for 10 minutes with lag p99 ≤ 2s, 0 duplicates and 0 missing runs.

---

## 10. Phase 4: deployment

### T-401 Helm chart
**Status:** todo · **Needs:** T-201, T-221, T-241, T-261 · **Owns:** `deploy/helm/kairos/**`

- [ ] One chart with a Deployment per service (`kairos-api`, `kairos-watcher`, `kairos-executor`, `kairos-state`), a pre-upgrade Job for `kairos-migrations`, and a CronJob for `kairos-maintenance` partitions.
- [ ] Pod security: `runAsNonRoot`, `runAsUser: 10001`, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, the `RuntimeDefault` seccomp profile, and an `emptyDir` for `/tmp`.
- [ ] Probes on 8081 (`/actuator/health/liveness`, `/actuator/health/readiness`), resource requests and limits, `terminationGracePeriodSeconds` 75 for executors (60s drain), PodDisruptionBudgets.
- [ ] Autoscaling: HPA for the API on CPU; KEDA for executors on Kafka lag and for state on result-topic lag.
- [ ] NetworkPolicies: default deny; allow the API from the ingress; allow services to reach Postgres, Kafka and Redis; allow executors egress only to internal target ranges on allowed ports.
- [ ] Secrets through the Secret Manager CSI driver and Workload Identity (no Kubernetes Secret manifests with values in the chart). Kafka connection settings for production: `KAIROS_KAFKA_SECURITY_PROTOCOL=SASL_SSL`, `KAIROS_KAFKA_SASL_MECHANISM`, `KAIROS_KAFKA_USERNAME`, `KAIROS_KAFKA_PASSWORD` (from Secret Manager) and, when the brokers use a private CA, `KAIROS_KAFKA_TRUST_CERTIFICATE`; compose stays `PLAINTEXT`.
- [ ] `values.yaml` documented, with `values-kind.yaml` and `values-gke.yaml`.
- [ ] `helm lint` and `kubeconform` run in CI.

**Acceptance:** `helm template` output passes `kubeconform` and a policy check (no root, no privilege escalation, limits set).

### T-402 Terraform for GCP
**Status:** todo · **Needs:** none (can start in wave 1) · **Owns:** `deploy/terraform/**`

- [ ] Modules: network (VPC, private subnets, Cloud NAT), GKE (private cluster, Workload Identity, node pools for executors), Cloud SQL (one control and two cell instances, private IP, HA, PITR, IAM database auth), Managed Service for Apache Kafka (topics per LLD section 4.2 with replication factor 3), Memorystore for Redis (TLS, AUTH), Secret Manager secrets (values set outside Terraform), Cloud KMS key ring and keys (KEK), Artifact Registry, IAM service accounts per service with least privilege.
- [ ] Remote state in GCS with locking; provider versions pinned with a `.terraform.lock.hcl`.
- [ ] `terraform fmt`, `validate` and `tflint` run in CI; there is no apply pipeline (learning project).

**Acceptance:** `terraform validate` and `tflint` pass, and `terraform plan` succeeds with a dummy project id in CI (offline providers mocked where needed).

### T-403 CI pipeline
**Status:** todo · **Needs:** T-002 · **Owns:** `.github/workflows/**`, `.github/dependabot.yml`

- [ ] Workflow on pull requests and on `develop`: `make build`, `make test` (Testcontainers on the runner's Docker), the OpenAPI export diff, `make images`, a Trivy scan of the images (fail on high or critical vulnerabilities with a fix available), an SBOM (Syft) uploaded as an artefact, `helm lint`, and the Terraform checks.
- [ ] Every third-party action pinned by full commit SHA with the version in a comment; `permissions` set to the minimum per job; no long-lived cloud credentials.
- [ ] Gradle dependency verification enforced in CI (the build fails on a checksum mismatch).
- [ ] Dependabot for Gradle, GitHub Actions and Docker base images; every update PR passes the same pipeline and refreshes lockfiles and checksums.

**Acceptance:** a pull request shows all checks; a tampered checksum in `verification-metadata.xml` fails the build.

### T-404 kind validation
**Status:** todo · **Needs:** T-401 · **Owns:** `deploy/kind/**`, `make kind-*` targets

- [ ] A kind cluster config and a script that installs Postgres, Kafka and Redis in-cluster (charts pinned by version and digest), loads the locally built images, and installs the Kairos chart with `values-kind.yaml`.
- [ ] `make kind-up`, `make kind-test` (runs the `smoke` profile and the end-to-end scenarios as a Kubernetes Job), `make kind-down`.

**Acceptance:** on kind, all pods become ready, the migrations Job completes, and the smoke profile passes with 0 duplicates.
