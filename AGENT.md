# Project Context

Kairos architecture, engineering workflow, accepted decisions, and implementation status. Read alongside [AGENTS.md](AGENTS.md), [HLD.md](HLD.md), [LLD.md](LLD.md), and [IMPLEMENTATION_CHECKLIST.md](IMPLEMENTATION_CHECKLIST.md).

## Commands (Docker only, never JDK/Gradle on the host)

Gradle runs through `./gradlew` inside the pinned `eclipse-temurin` JDK 25 image (see `Makefile`). The Gradle home is the `kairos-gradle-home` volume, and the Docker socket is mounted so Testcontainers can start Postgres, Kafka and Redis.

| Task | Command |
|---|---|
| Compile, unit tests, all quality gates, boot jars | `make build` |
| Fix formatting (the build fails on unformatted code) | `make gradle ARGS=spotlessApply` |
| All checks incl. Testcontainers ITs | `make test` |
| One module's tests | `make gradle ARGS=":libs:kairos-domain:test"` |
| One test class/method | `make gradle ARGS=":libs:kairos-domain:test --tests '*Cron*'"` |
| Long runs, detached (preferred) | `make bg ARGS=check`, then `make bg-logs` / `make bg-wait` (container `kairos-gradle`) |
| After adding/upgrading a dependency | `make locks` (rewrites `*.lockfile` + `gradle/verification-metadata.xml`; never hand-edit them) |
| Stack (detached, `--wait`) | `make up` (API on `127.0.0.1:8080`, Swagger at `/swagger-ui.html`), `make up-all` adds Prometheus/Grafana |
| Status / logs / stop | `make ps`, `make logs S=kairos-executor`, `make down`, `make clean` (also drops volumes) |

- The compiler runs with `-Xlint:all -Werror`, so any warning fails the build. Generated protobuf code is exempt.
- **Quality gates (T-002), all run by `make build`:** Spotless (palantir-java-format for Java, ktlint for `*.gradle.kts` and `build-logic` Kotlin); Error Prone + NullAway in JSpecify mode on `libs/*` (every `dev.kairos` package is null-marked, so a nullable reference needs `@Nullable`; NullAway is off for test code); doclint Javadoc on `libs/*` public API (skipped while a module has only `package-info.java`); JaCoCo line coverage of 80% for `libs/*` and 70% elsewhere (`*Application` classes and generated proto excluded; aggregated report is the root `testCodeCoverageReport`); ArchUnit rules from `config/archunit/` compiled into **every** module's `test` task, so new modules are covered automatically. Gradle configuration cache is on.
- Dependencies are added to `gradle/libs.versions.toml` with exact versions, in alphabetical order, from Maven Central or the Gradle Plugin Portal only.
- Infra ports are not published. Use `docker compose -f deploy/compose/docker-compose.yml exec postgres psql -U postgres -d kairos_cell0`.
- Branches are `t-<id>-<name>`, one checklist task per PR, against `develop`. Commits use Conventional Commits scoped by module (`feat(api): ...`). A task is claimed by setting its checklist status to `in progress (name)` (statuses: `todo` → `in progress` → `review` → `done`), and sub-task boxes are ticked in the PR that completes them. Only T-002/T-003 add modules to `settings.gradle.kts`.

## Code architecture

- **Build:** `settings.gradle.kts` lists every module. Shared configuration lives in the `build-logic/` convention plugins: `kairos.java-conventions` (toolchain, locking, Spring Boot BOM, `-Werror`), `kairos.library-conventions` (`libs/*`: Error Prone, Javadoc, 80% coverage), `kairos.formatting-conventions`, `kairos.coverage-conventions`, `kairos.archunit-conventions`, `kairos.root-conventions` (root formatting + aggregated coverage), `kairos.errorprone-conventions`, `kairos.dependency-locking` (`resolveAndLockAll`, used by `make locks`), `kairos.proto-conventions` and `kairos.service-conventions` (Spring Boot, actuator, Prometheus, `bootJar` named `app.jar` so the single `deploy/docker/Dockerfile` can build any module).
- **Module dependency rules** (enforced by ArchUnit, T-002): `kairos-domain` is plain Java (JDK, Guava hashing, cron-utils only; no Spring, Jakarta, JDBC or Jackson). `kairos-proto` holds the Protobuf Kafka contracts. `persistence`/`messaging`/`security`/`http` depend on domain and proto. `kairos-engine` (run lock, claims, outbox) builds on all of them. Services, jobs and tools depend on libs, never on each other.
- **Integration tests (T-003):** `libs/kairos-test-support` (`testImplementation` only) supplies `@KairosIntegrationTest(postgres=…, kafka=…, redis=…)`, which starts shared Testcontainers once per JVM, pinned to the compose images, and tags the class `integration`. Plain JUnit tests inject `KairosPostgres`/`KairosKafka`/`KairosRedis`. Spring tests get `kairos.datasource.*`/`kafka.*`/`redis.*` bound automatically, so they need no `@DynamicPropertySource`. Turn off the containers a test doesn't use. The module also has a mutable clock and `Awaits` polling helpers.
- **Service packages are feature slices:** `dev.kairos.<service>.<feature>.{web,application,infrastructure}` plus `config/` and `support/`. `web` → `application` only. `application` uses ports and never touches Spring web, JDBC or Kafka types. Only `infrastructure` touches `JdbcClient`, `KafkaTemplate` or Lettuce.
- **Runtime flow:** `kairos-api` (REST) → `kairos-watcher` (shard leases, fire/delayed pollers, outbox relay to Kafka, sweepers) → `kairos-executor` (four ordered execution gates: Redis lock, fenced DB claim, send-time guard, sent marker; then the HTTPS call) → `kairos-state` (batched settlement, retries/DLQ, DAG advancement). The control DB holds teams, users and endpoints. Cell DBs (shards 0–127 and 128–255) hold definitions, schedules and runs. Every cell call goes through `CellRouter`, and no transaction spans two DBs.
- **Conventions that matter:** no JPA (use `JdbcClient` with named params and set-based SQL, `FOR UPDATE SKIP LOCKED`), no Lombok, constructor injection, records and sealed result types, an injected `Clock` (never `Instant.now()`), `@ConfigurationProperties` records under `kairos.*`, and Flyway scripts only in `jobs/kairos-migrations`, named by timestamp (`V2026_10_20_1530__x.sql`) and never edited after merge. REST contracts must match `LLD.md` section 3 exactly, and a contract change updates LLD.md in the same PR.
- **Source of truth:** `LLD.md` > `IMPLEMENTATION_CHECKLIST.md` (tasks, "Owns" paths, definition of done, engineering standards in section 4) > this file. Only modify the paths a task owns.
- **State (2026-10-23):** see the "Progress and next steps" section below.

## Progress and next steps (update at the end of every session)

**Status at end of session (2026-10-23).** T-203c is merged through PR #25 (`055e6d8`), and T-104 workflow DAG validation/templates through PR #26 (`5e0e5b7`); both checklist statuses are `done`. T-221 shard leases and node registry is in progress on `t-221-shard-leases`. All Phase 1 library tasks are merged; other runtime services remain skeletons.

**T-104 verification:** Docker `make bg BG_NAME=kairos-gradle-t104 ARGS="spotlessApply build check"` passed with strict dependency verification: 2,136 tests, no failures/errors/skips; domain line coverage 99.55%. New tests cover bounded DAG plans, cycles, ancestor references, injection rejection, typed JSON insertion, sensitivity and output limits, plus 300 generated valid-DAG and 100 back-edge trials. No dependency, migration or service wiring changes.

**T-104 integration contract:** T-206 resolves endpoint output declarations before constructing `WorkflowTask` and rejects duplicate JSON task keys before converting to maps. `WorkflowValidator` computes the immutable trusted plan; independent roots are valid. T-243 adapts Jackson trees through plain Java values to `JsonValue`, uses `OutputExtractor`, seals sensitive outputs before storage/publication, and handles extraction error classes and rendering `RENDER_FAILED`. PATH/QUERY renderer results are already percent-encoded: preserve these components when assembling HTTP requests; `OutboundRequest.withQueryParameter` accepts raw values and would double encode them. A null request body means no body; `JsonValue.NullValue` means explicit JSON null. See LLD section 3.7 for resource bounds and selector syntax.

**T-203c verification:** Docker `make bg BG_NAME=kairos-gradle-t203c ARGS="spotlessApply build check"` passed with strict dependency verification: 2,108 tests, no failures/skips; API line coverage 94.89%. The fresh isolated Compose CRUD smoke test passed, including API-key management of encrypted secrets and public CA bundles, duplicate/invalid-input errors and audit redaction; its containers and volumes were removed. Additional integration cases verify deletion protection for retained endpoint versions. Credentials are bound by AES-GCM associated data to team/name/version; only metadata is returned. Lazy keyset initialization and optimistic rotation retries are race-safe; every mutation and audit entry commits together. Credential fields have explicit JSON string type checks, bounded sizes and kind-specific shapes; diagnostic strings are redacted. CA input must be certificate PEM only, at most 64 KiB and 10 currently valid CA certificates, with certificate-signing usage when present. Both lists paginate by name with team/list-bound cursors. No migration or production dependency changes; existing pinned Bouncy Castle artifacts were added to API test dependencies and the module lockfile regenerated. Test private keys stay in memory.

**T-204 integration contract:** inject SecretLookup and CaBundleLookup for metadata-only validation. Lock the caller's team row FOR UPDATE in the endpoint mutation's control transaction before validating or changing references; T-203c deletion uses that same lock. Deletion checks current endpoint specs and retained api_endpoint_versions so pinned configurations retain credentials/trust anchors. The LLD records credential-name/value limits and clarifies that PUT secret rotation creates a version without If-Match. T-243 still owns credential loading/decryption and its 60s cache; T-203c makes new versions durable immediately.

**T-203d verification:** Docker `make bg BG_NAME=kairos-gradle-t203d ARGS="spotlessApply build check"` passed: 2,088 tests, no failures/skips; API line coverage 94.24%. A fresh isolated Compose stack applied the overlap migration, became healthy, and passed onboarding, two signing rotations, API-key refusal on rotation, audit reads with an API key, action filtering and cursor pagination, then was removed. Rotation uses optimistic keyset replacement and atomic audit writes, with KEK operations outside transactions. `team_keysets.previous_key_valid_until` persists the returned deadline (new Flyway migration `V2026_10_23_1530__signing_key_overlap.sql`). Defaults: `kairos.api.signing.overlap=24h`, `prune-interval=60s`, `prune-batch-size=100`. Cleanup removes non-primary keys after the persisted deadline and cannot overwrite concurrent rotations. Targets must enforce their own verification-key deadlines. Existing account/team/API-key audit adapters delegate to `AuditWriter`; details accept bounded scalar metadata and reject secret fields/objects. Audit cursors retain the original time/action filter snapshot and are team-bound. No dependency changes. Unrelated local configuration edits and the lesson PDF remain excluded.

**T-203b verification:** Docker `make bg BG_NAME=kairos-gradle-t203b ARGS="spotlessApply build check"` passed: 2,067 tests, zero failures/skips, API line coverage 93.78%. An isolated Compose stack on port 18080 became healthy and passed signup/login/team/refresh/key create/authenticate/list/revoke, then was removed. All three management endpoints require a user token. Usage tracking is a feature-local interceptor plus bounded Caffeine coalescing and a SQL one-minute guard; the existing authentication cache enforces the 60s revocation bound. Creation/revocation audit entries commit with the mutation; T-203d now routes these through the shared writer. Pagination uses the existing `(team_id, created_at, key_id)` index and team/list-bound HMAC cursors. No dependency versions or lockfiles changed; Gradle generated missing Bouncy Castle 1.84 and JUnit 5.13.1 BOM `.module` checksums. Unrelated local configuration changes and the lesson PDF are excluded from the task commits.

**Done, merged to `develop` (22 tasks + 5 follow-ups):**
- API: T-201 foundation (#18), T-202 accounts APIs 1–7 (#19), T-203a teams/members APIs 8–12 (#20), T-203b API keys APIs 13–15 (#23), T-203d signing/audit APIs 23–24 (#24), T-203c secrets/CA bundles APIs 16–22 (#25), T-104 workflow DAG validation/templates (#26).
- Tooling: T-001 skeleton, T-002 quality gates (PR #2), T-003 test support (#1), T-112 observability (#6).
- Libraries: T-101 ids/shards (#4), T-102 schedules (#21), T-103 retry/outcomes/run state machine (#8), T-105 proto contracts (#3), T-106 migrations (#5), T-107 persistence + cell routing (#7), T-108 messaging (#15), T-109 security (#10), T-110 outbound HTTP client + target policy (#12), T-111 Redis run lock + sent markers (#11), T-113 run creation + outbox writer (#9).
- Follow-ups: dead-letter `fence` column + LLD/checklist fixes (#13); engine Spring configuration `OutboxConfiguration`/`RunCreationConfiguration` (#14); trace/key docs (#16); migrations create the JWT signing keyset after migrating (#17, closes old open item 1).

**Not yet merged (29 tasks; T-221 in progress):**
- `kairos-api`: T-204 endpoints, T-205 jobs, T-206 workflows, T-207 runs/DLQ/stats.
- `kairos-watcher`: T-221 shard leases, T-222 fire poller, T-223 delayed poller, T-224 outbox relay, T-225 recovery sweepers, T-226 singleton tasks.
- `kairos-executor`: T-241 dispatch consumers, T-242 execution gates, T-243 render/call/classify/publish, T-244 cancel + drain.
- `kairos-state`: T-261 result settler, T-262 workflow progression, T-263 stats aggregator.
- `kairos-maintenance`: T-281 partition manager, T-282 operator commands.
- Verification: T-301 mock target, T-302 bench CLI, T-303 cross-module suites, T-304 end-to-end, T-305 resilience/duplicate proof, T-306 per-cell write test.
- Deployment: T-401 Helm, T-402 Terraform, T-403 CI, T-404 kind.

**Ready after the user selects the next task:** T-207, T-241, T-261, T-281, T-282, T-301, T-402, T-403. T-204 can use the merged metadata lookup ports; its endpoint-test operation still needs the T-243 executor interface. T-241 remains an independent entry point into executor services. Wait for user go-ahead before another task.

**Open items:**
1. Done (#17): migrations bootstrap the JWT keyset.
2. T-243 must wire T-104's JSON adapter/output extraction, sensitive-output sealing and already-encoded HTTP components (see integration contract above).
3. T-111 metric names `kairos_run_lock_acquire_total` and `kairos_redis_script_duration_seconds` are not yet in the T-112 metric table; the Redis readiness health indicator is left to a service task.
4. `kairos.engine.runs.dispatch-horizon` (default 5s) must equal the fire poller look-ahead (T-222 should read the same property). The engine's `OutboxConfiguration` owns the single `KairosIdFactory` bean; services inject it and never define their own.
5. Implemented in T-203d: signing-key pruning uses the persisted `previousKeyValidUntil`.
6. Implemented in T-203d: account/team/API-key audit adapters delegate to the shared AuditWriter, preserving existing typed feature ports and transaction boundaries.
8. T-201 session-cache fix is merged (#22): a refreshed token whose team differs from the cached state re-reads once. Still open from T-203a: `addedAt` = `users.created_at` until a `users.team_joined_at` migration.
7. T-202's `LoginTimingIntegrationTest` compares wall-clock medians (unknown vs known email within 20%) and may be flaky on a loaded CI runner (watch in T-403).

**T-202 choices (accepted by user, 2026-10-21):** login lockout from 5 consecutive failures, 1m doubling to 1h (`kairos.api.accounts.lockout.*`); API keys on `/v1/me` and `/v1/me/password` get 401 `unauthenticated` (LLD rows), not 403; missing `refreshToken` is 400 `validation`, a bad one 401 `invalid-refresh-token`; bulk revocations (password change, refresh reuse) audited as `SESSIONS_REVOKED`, lockout as `LOGIN_LOCKED`; test fixtures also apply the day-partitions migration so `audit_log` has partitions.

**Decisions recorded this session (user, 2026-10-20/21):** T-110 auth formats (`API_KEY {location: HEADER|QUERY, name}`, OAuth2 `{tokenUrl, scope?}`), whole-exchange deadline = `timeoutMs`, token-endpoint failures classified by the token endpoint's own status, all in LLD; libraries expose explicit `@Configuration(proxyBeanMethods = false)` classes that services `@Import` (checklist section 4.3, not LLD). **Work one task at a time and wait for the user's go-ahead before starting the next (user, 2026-10-21).**

**How work is run in this project:** work one task at a time on a `t-<id>-<name>` branch based on `origin/develop`. Use an isolated worktree when needed to preserve unrelated work. Build with a unique container name (`make bg BG_NAME=kairos-gradle-t<id> ARGS=...`, same for `bg-wait`/`bg-logs`) so builds do not collide. The first commit claims the task (and flips any merged-but-`review` statuses), the last ticks sub-tasks and sets `review`. Before reporting, verify the pushed branch: the configured Git identity, no assistant-attribution trailers, no secrets, paths limited to the task's "Owns", and `git merge-tree` against `develop` and any related active branch. When rebasing onto a newer `develop`, take either side of lockfile/`verification-metadata.xml` conflicts and regenerate them with `make locks`, run the full check, then `git push --force-with-lease=<branch>:<old-sha>`. The user opens and merges PRs themselves (no `gh` CLI on this machine).

## Project status

- **Phase (2026-10-18):** HLD.md and LLD.md approved and pushed. Repository skeleton (T-001) is built and verified locally: Gradle 9.8.1 wrapper, Java 25, lockfiles + SHA-256 verification metadata, shared `deploy/docker/Dockerfile`, `deploy/compose/docker-compose.yml`, `Makefile`. `IMPLEMENTATION_CHECKLIST.md` at the root lists tasks T-002 to T-404 for parallel agents. Everything runs in Docker only and detached (`make up`, `make bg ARGS=...`); never run JDK/Gradle/services on the host. Hooks block any command or file mentioning `.env`; verify the stack with in-memory credentials under compose project `kairos-verify`.
- **Workflow the user set:** HLD → user review and confirmation → LLD → implementation. HLD and LLD are approved, so implementation follows `IMPLEMENTATION_CHECKLIST.md`. Still get the user's confirmation before starting each new stage.
- **Goal:** a production-grade distributed job scheduler (Airflow/Temporal/Celery style), built with best practices and design patterns.
- **Decisions made by the user (2026-10-12):**
  - **Baseline design = the original PDF lesson** (summarised below). Production research is used only for explicit, justified deviations, which are now recorded neutrally in HLD section 14 (Key decisions).
  - **Jobs are synchronous HTTP API calls to internal endpoints only.** No script, shell or container execution.
  - **API configuration lives in a per-tenant `api_endpoints` registry**, scoped by `tenant_id` rather than one physical table per tenant. Jobs and tasks reference endpoints by name. Auth type `NONE` must be supported.
  - **Retries:** 5 retries at queue level with exponential backoff and jitter (30s base, 10-minute cap, about 15 minutes before the DLQ; confirmed). After that, the run goes to the DLQ for manual triage, with auto-protection (HLD section 10).
  - **DAG workflows are in scope, with data passing.** Upstream response outputs (body or headers) can be injected into downstream request body, headers, path or query (LLD section 2/3, workflows).
  - **Deployment target: Kubernetes on GCP** (GKE, Cloud SQL, Managed Kafka, Secret Manager and KMS) with Terraform and Helm. This is a learning project and will **not be deployed**, but it must be production grade. Validate locally on `kind`.
  - **Auth for the scheduler API: per-tenant API keys** with scopes, stored as HMAC-SHA256 hashes with a pepper from Secret Manager, shown once, rotatable (HLD section 7).
  - **Auto-protection on by default:** endpoint auto-suspend and job auto-pause (after 3 consecutive dead-lettered runs), with opt-out per endpoint or job.
  - **Retention: 7 days** for run history, task outputs and resolved DLQ entries. Unresolved DLQ entries are never purged automatically.
  - **Stack: Java, Spring Boot, PostgreSQL, Kafka, Redis.** Redis was re-added in HLD v1.1 for execution locks, sent markers and API rate limits.
  - **NON-NEGOTIABLE (user, 2026-10-12): at-least-once execution via the transactional outbox pattern (explicit `outbox` table + relay), and no duplicate job execution via a Redis distributed lock.** The design is in HLD section 6 (Execution guarantees): a fence-aware Redis lock + fenced DB claim + send-time guard + sent marker + `unknownOutcomePolicy`. Never remove or weaken either mechanism.
  - **Target (revised 2026-10-13): 20,000 executions/s sustained and 50,000/s peak (design for the peak).** Reference production: control DB + 2 cells (shards 0–127, 128–255). Infra and resource sizing deferred.
  - **Teams, kept simple (revised 2026-10-13, replaces organizations):** an **internal platform**. **Signup = email + password; login = email + password. No email verification, no invitation or reset emails, nothing sends email** (user, 2026-10-13). A new user has no team (403 `no-team` on scheduling APIs) and either creates one (`POST /v1/teams`) or is added by any existing member by email (`POST /v1/team/members`); refresh picks up the team into the JWT. One team per user (`users.team_id` nullable). **No roles** (every member equal; any member can pause/cancel/delete jobs, add/remove members). Team comes from the token/API key only; flat routes `/v1/jobs` etc.; cross-team ids return 404. **No egress rules or approval**: any internal API may be scheduled, guarded only by a platform-wide target policy (internal suffixes/CIDRs, ports, fixed deny list, DNS-rebinding defence). **No platform admin API**: quotas from config defaults; operators use the `kairos-maintenance` CLI (`team set-quota`, `user disable`, `user reset-password`). Jobs/workflows can be deleted (status DELETED, name reusable). Focus is the core scheduler, not user management.
  - **Database choice (2026-10-13):** stay on sharded PostgreSQL cells, not Cassandra (HLD sections 8 and 14): the outbox, fenced claims, unique occurrences and atomic settlement need transactions. Distributed SQL (Spanner/CockroachDB) is the fallback. Cell count (2, or 4 of 64 shards) is confirmed by the M2 per-cell write test.
  - **Repo shape:** a monorepo with four microservices (`services/kairos-api`, `kairos-watcher`, `kairos-executor`, `kairos-state`), one image each, plus `libs/*` (domain, proto, persistence, messaging, security, http, engine), `jobs/*` (migrations, maintenance) and `tools/*` (mock-target, bench-cli).
  - **Sharding:** `shard = murmur3(tenant_id + name) mod 256`; names are immutable; job, workflow, run and DLQ ids embed the shard in their low 8 bits; `api_idempotency` lives in the database that owns the resource.
  - **Lost-response default:** `unknownOutcomePolicy = RETRY_WITH_IDEMPOTENCY_KEY`.
  - **Project name: Kairos.** Java package root `dev.kairos`; Gradle modules and deployables `kairos-*` (e.g. `kairos-domain`, `kairos-api`); config prefix `kairos.*`; env prefix `KAIROS_`; metrics `kairos_*`; Redis keys `kairos:*`; Kafka topics `kairos.run.*`; HTTP headers `X-Kairos-*`. SQL schema, table and role names stay functional (`scheduler`, `sched_*`).
- **Security:** every code artefact follows the security checklist in IMPLEMENTATION_CHECKLIST section 4.8. In particular: HTTPS-only targets, parameterised SQL only, no hardcoded secrets, and API keys stored as HMAC hashes. See HLD section 11 and IMPLEMENTATION_CHECKLIST section 4.8.
- **Current design:** HLD.md and LLD.md take priority over sections 1–14 below wherever they differ (HLD section 14 lists the key decisions). The sections below are the baseline lesson record, **not** the current design. For example, they describe 3 retries, scripts and containers as job types, and `/v1/api` routes, and all of these are superseded.

## Documentation conventions (user requirement, 2026-10-12)

- **Repository documentation:** `AGENTS.md` is the contributor guide; `AGENT.md` holds project context and references. Commit both alongside the design documents, implementation checklist, and `assets/kairos-flow.svg`. Keep the LLD in one file. The lesson PDF and `.local/` tooling remain local; do not add them to commits.
- **Keep docs lean (user, 2026-10-13):** the LLD contains only (1) database design: entity relationships plus each table's columns and data types, (2) the API list with contracts, each API as a collapsible `<details>` block with an `http` request code block (URL, headers, path/query params, body), the success response, an errors table plus problem+json bodies, and the background effect, generated by `.local/apigen/gen.py` so every JSON example is valid, (3) background jobs with their parameters, plus Kafka topics and Redis keys. No SQL queries, no pseudo-code, no repo layout or milestones. The HLD is short and built around **one** end-to-end diagram (onboarding, registering and scheduling, execution, retries and DLQ, dependent tasks) using real Kairos names.
- **Write throughput as 20K / 50K**, never 20,000 / 50,000. **Never use the section-sign symbol** in any document; write "section 7" or "part 05, section 2".
- **HLD and LLD must read as production design documents written by an engineer.** That means:
  - no mentions of the lesson, PDF, research notes, "baseline", deviations, AI tools or review conversations;
  - no "you" or "your decision" phrasing;
  - decisions are recorded neutrally (HLD section 14 "Key decisions", LLD section 1), and revision history tables are fine.
- **Diagrams are hand-drawn (Excalidraw-style) SVGs, not Mermaid code blocks.**
  - The source is `.local/diagram-src/kairos-flow.mmd` and the rendered SVG is `assets/kairos-flow.svg`, referenced as an image from HLD.md and README.md.
  - Render with `.local/diagram-tools/render.cjs`: Mermaid "handDrawn" look, the Virgil font embedded, white background, valid XML, labels as SVG text.
  - Command: `NM=<node_modules containing puppeteer + mermaid> node .local/diagram-tools/render.cjs .local/diagram-tools/cfg.json .local/diagram-tools/Virgil.woff2 assets .local/diagram-src/kairos-flow.mmd`
  - Visually check with `shot.cjs`.
  - Use class styles with fill only (`fill:#a5d8ff` and so on); setting `stroke` darkens the hatching.
  - Sequence diagrams don't support the hand-drawn look, so draw flows as flowcharts with numbered steps.
  - To lay out a row inside a subgraph, link subgraph to subgraph rather than node to node.

## Historical Lesson Reference

The following source notes are preserved for reference. They are superseded by the approved HLD, LLD, and current implementation checklist; do not use their older routes, retry counts, scale assumptions, or arbitrary-code execution examples as implementation requirements.

## Source reference

- Lesson: InterviewWithBunny, *System Design 9: Design Distributed Job Scheduler like Airflow | Temporal | Celery*. URL: https://www.interviewwithbunny.com/systemdesign/09
- Local copy: the 25-page PDF print of that page in the repo root. Its text is identical to the lesson, but the design diagram did not render in it, so use the image link below.
- Video: https://youtu.be/hLvB2haod5w
- Hand-drawn HLD/LLD diagram: https://ik.imagekit.io/interviewwithbunny/kjn24ht84tyq384tq3t4o3gb4og42b.jpeg
- The page is a client-rendered SPA, so WebFetch returns only the shell. The real content is at `https://ik.imagekit.io/interviewwithbunny/courses21_7.json`, under `courses.systemdesign.videos[id="09"].refresher`. The cheat sheet there was last updated 2026-01-20.

**Production research:** [PRODUCTION_RESEARCH.md](PRODUCTION_RESEARCH.md) compares this design with how real systems work: Airbnb, Dropbox, PhonePe, Meta, Slack, Google, Netflix, Uber, Airflow, Temporal, Postgres queues and others, plus postmortems. Sections 0 and 10 list the design changes it recommends. **Read it before changing the architecture.** The user chose the PDF as the baseline, so research findings are applied only as explicit deviations recorded in HLD section 14.

**One-liner:** DAG-based scheduling → Redis/DB for the job queue → multiple executors → Kafka for run/retry/dead-letter → Watcher for monitoring.

---

## 1. Functional requirements

1. Schedule a job as **immediate**, **future (one-time timestamp)** or **cron expression** (an `interval` type also exists in the schema).
2. Monitor job status in real time: `pending, running, success, failed, cancelled`.
3. Update or cancel a scheduled job before it runs. Cancel a running job.
4. **Job dependencies (DAG)** that control execution order. *The video's diagram marks DAG as out of scope. The cheat sheet includes it, so treat it as a later milestone.*
5. Retries with a configurable retry count and backoff strategy.
6. A dead-letter queue for jobs that fail permanently.
7. Job priority and per-job resource limits (CPU, memory).

## 2. Non-functional requirements

| Area | Requirement |
|---|---|
| Volume | Millions of jobs/day. Thousands/sec at peak (the diagram says **10K jobs/sec**). |
| Executors | Hundreds of executor instances (100–1000) running in parallel. |
| Latency | A job runs within **±2s of its scheduled time**. |
| CAP | **Availability over consistency.** Eventual consistency is acceptable. |
| Guarantee | **At-least-once** execution, so jobs must be idempotent. |
| Durability | Job state is persisted. No job is lost on a system failure. |
| Cron | Standard cron expressions (e.g. `0 0 * * *` = daily at midnight). |
| Time zones | Users schedule in their own time zone. Storage and scheduling use UTC. |
| Backfill | Missed runs (e.g. while the scheduler was down) can be run later. |

## 3. Core entities

- **Job**: the task definition (job_id, name, schedule as cron or timestamp, payload, dependencies, retry config, timeout, priority).
- **JobRun**: one execution instance (run_id, job_id, status, attempt, start/end time, executor_id, error, logs URL).
- **Scheduler (Watcher)**: polls for due jobs and publishes them to Kafka.
- **Executor**: a worker that runs the job logic, reports status, heartbeats and handles cancellation.
- **DAG**: a workflow of dependent jobs.

## 4. API (`/v1/api`)

**Job management**
- `POST /v1/api/jobs`: create or schedule a job. Body: `{name, schedule_type, schedule_value, timezone, payload, retry_count: 3, timeout, priority, dependencies: []}`. Returns `{job_id, status: "scheduled", next_run_time}`.
- `GET /v1/api/jobs/{jobId}`: job details (schedule, status, last run time).
- `GET /v1/api/jobs/{jobId}/status`: current execution status.
- `PUT /v1/api/jobs/{jobId}`: update the schedule or payload.
- `POST /v1/api/jobs/{jobId}/cancel`: cancel a scheduled or running job.
- `POST /v1/api/jobs/{jobId}/runnow`: ad-hoc immediate run. This pushes straight to Kafka `run` and skips the watcher.

**Monitoring**
- `GET /v1/api/jobs/runs`: list runs, filtered by status and date range.
- `GET /v1/api/jobs/{jobId}/runs`: execution history for one job.
- `GET /v1/api/jobs/stats`: totals, success rate and average execution time.

**Internal**
- `POST /executor/run` `{run_id, job_id, payload}`: the Job Consumer dispatches a run to an executor.

## 5. High-level architecture

```
clients ─► LB + API Gateway (authN/authZ, rate limiting, routing)
             ├─► Job Svc ──── create/update/cancel ────► Job DB (PostgreSQL)  ◄── read ── Job Search Svc
             │     └── run-now / immediate ──► Kafka[run]                                  (history, filters, stats;
             └─► Job Search Svc                                                             reads go to replicas)

Watcher (every ~20s) ── SELECT due jobs (batch 1000) ──► Job DB
   │  ├── lock:job:{id}:schedule (SETNX), last_poll_time ──► Redis
   │  └── publish ──► Kafka topics: run | retry | dead | cancel | completed
   └── health check (every 30s): stale executor heartbeats → reschedule orphaned runs

Job Consumer Svc (group "job-consumers", consumes run + retry)
   ├── insert JobRun(status=pending)
   ├── choose an executor via Redis executors:available (least active jobs)
   └── POST /executor/run ─► Executor Svc × N (100s)

Executor Svc
   ├── status / timestamps ─► Job DB      ├── heartbeat every 10s ─► Redis
   ├── logs (async) ─► S3 / CloudWatch / ELK (only the URL goes in the DB)
   ├── failure with retries left ─► Kafka[retry]    ├── retries exhausted ─► Kafka[dead] + alert
   └── listens on Kafka[cancel] (or a Redis cancel flag) → SIGTERM, then SIGKILL after 30s

Retry Consumer ◄─ Kafka[retry]: wait until scheduled_retry_at, then republish to Kafka[run]
Dependency Watcher ◄─ Kafka[completed]: find jobs depending on the finished job; publish them once all deps are met
```

Extra details from the diagram:
- The watcher pulls jobs scheduled in a **look-ahead window `[now, now+5min]`** and marks them `queued`. This look-ahead is how the ±2s target can be met with a 20s poll interval.
- The watcher also re-picks runs whose status is `running` but whose `modified_time` is more than **15s old** (stuck or orphaned runs).
- The diagram shows Job Svc writing submits and edits through Kafka → Consumer Svc → Job DB (async write path), as an option.
- The diagram's alternatives to the watcher/Kafka path are a **Redis Sorted Set** (score = run timestamp) and **Amazon SQS** (delivery at an exact timestamp).

## 6. Data model

### PostgreSQL `jobs` (master job definitions)
| column | type / notes |
|---|---|
| job_id | uuid PK |
| name | varchar(255) UNIQUE |
| schedule_type | enum(`cron`, `one_time`, `interval`) |
| schedule_value | varchar(255): cron expression or ISO timestamp |
| timezone | (implied by the time-zone Q&A) the user's IANA zone, used for display and DST recalculation |
| next_run_time | timestamp (UTC), **indexed**, used for polling |
| last_polled_time | timestamp, prevents duplicate scheduling |
| status | enum(`scheduled`, `running`, `paused`, `cancelled`) |
| payload | jsonb |
| dependencies | uuid[]: the job_ids this job depends on |
| retry_count | int, default 3 |
| timeout | int, seconds (default 3600) |
| priority | int 1–10 (10 = highest) |
| owner_id | uuid FK → users |
| created_at, updated_at | timestamp |

Composite index: **`(next_run_time, status, last_polled_time)`**, used by the watcher query.

### PostgreSQL `job_runs` (execution history)
| column | type / notes |
|---|---|
| run_id | uuid PK |
| job_id | uuid FK → jobs, **indexed** |
| status | enum(`pending`, `running`, `success`, `failed`, `timeout`, `cancelled`, `executor_died`), indexed |
| scheduled_at | when the run was supposed to start |
| start_time, end_time | actual times |
| execution_time_ms | bigint |
| executor_id | varchar(100) |
| attempt | int (1, 2, 3, …) |
| error_msg | text |
| logs_url | varchar(500): an S3 path or CloudWatch link. **Never store logs in the DB.** |
| modified_time | indexed (from the diagram), used for stuck-run detection |
| created_at | **indexed**, used for history queries |

Idempotency: a **UNIQUE constraint on `(job_id, scheduled_at)`** stops the same scheduled occurrence from running twice.

### Redis keys
| key | type | purpose |
|---|---|---|
| `lock:job:{job_id}:schedule` | STRING `{watcher_id}` `NX EX 60` | distributed scheduling lock |
| `executor:{executor_id}:heartbeat` | STRING timestamp, refreshed every 10s | executor liveness |
| `executors:available` | HASH `{executor_id: active_job_count}` | load balancing |
| `job:{job_id}:cache` | HASH, TTL 5 min | cached job definition (about 90% fewer DB reads) |
| `last_poll_time` | STRING timestamp | last successful watcher poll |
| `cancel:{run_id}` | flag with TTL (diagram alternative) | cooperative cancellation |
| `executor:{id}:current_run` | run_id | restart detection, so a killed run isn't re-executed |

### Kafka topics (retention 7 days for replay)
| topic | partitions | content |
|---|---|---|
| `run` | 10, keyed by job_id (keeps per-job ordering) | jobs ready to execute now |
| `retry` | 5 | failed runs with retries left |
| `dead` | 1 | permanently failed runs (DLQ) |
| `cancel` | 3 | cancel requests for running jobs (each executor uses its own consumer group so every executor sees every message) |
| `completed` | 10 | completion events for downstream systems and DAG resolution |
| `high/medium/low_priority_run` | — | optional priority tiers (see §8) |

Run message shape: `{job_id, run_id, payload, timestamp, attempt, scheduled_retry_at?}`

## 7. Core flows (LLD)

1. **Create:** validate the cron expression, check that a one-time job is in the future, and run a DFS cycle check on dependencies. Then insert the job (`status=scheduled`), compute `next_run_time` with a cron parser (e.g. croniter), and return `{job_id, status, next_run_time}`.
2. **Watcher poll** (every 20s, configurable, never below 10s):
   `SELECT … FROM jobs WHERE status IN ('scheduled','running') AND next_run_time <= NOW() AND last_polled_time < NOW() - 30s LIMIT 1000`.
   For each job: take the Redis lock, run a conditional `UPDATE … SET last_polled_time = NOW() WHERE job_id=? AND last_polled_time < NOW()-30s`, publish to `run` with `{job_id, run_id: uuid, payload, attempt: 1}`, advance `next_run_time` for cron jobs, release the lock. Publish to Kafka in batches.
3. **Consume:** the Job Consumer (group `job-consumers`) reads `run` and `retry`, inserts a JobRun (`pending`), picks an executor from `executors:available`, and calls `POST /executor/run`. The status becomes `running`.
4. **Execute:** the executor loads the job definition (Redis cache, then DB) and runs it in an **isolated process or container with CPU/memory limits** (default 0.5 vCPU and 512MB, up to 4 vCPU and 4GB). It streams logs asynchronously, heartbeats every 10s and enforces the timeout (kill the run and set `timeout`). At the end it writes `success` or `failed`, `end_time`, `error_msg` and `execution_time_ms`. **The Kafka offset is committed only after the job completes.**
5. **Retry:** if `attempt <= retry_count`, publish to `retry` with `scheduled_retry_at = now + delay`.
   `delay = base_delay * 2^(attempt-1) + random(0, base_delay/2)` with base 60s, giving about 60s, 120s and 240s. The Retry Consumer waits until `scheduled_retry_at`, then republishes to `run`. Once retries are exhausted (attempt > retry_count): publish to `dead`, mark the job permanently failed, and alert.
6. **DAG:** before scheduling job B, check that every dependency has a `success` run for the current period. If not, skip B until the next cycle. On a `completed` event, the dependency watcher publishes dependents whose dependencies are now all met. Siblings run in parallel. Cycles are rejected with DFS at create/update time, and DAG depth is capped (e.g. 10 levels).
7. **Cancel:** if the job is `scheduled`, set `status=cancelled` (it drops out of polling). If it is `running`, publish to `cancel`. The matching executor sends SIGTERM, allows 30s for graceful cleanup, then sends SIGKILL, and finally sets `cancelled` and releases locks. A cancel that arrives after the run finished is ignored.
8. **Health:** every 30s, check heartbeats. An executor with no heartbeat for **60s** (3 missed) is unhealthy. Its `running` runs become `executor_died`, and a new run with attempt+1 is published to `run`. Remove the executor from `executors:available` and alert.
9. **Duplicate prevention, in layers:** (a) Redis SETNX lock with a 60s TTL, (b) the conditional `last_polled_time` UPDATE, (c) the `(job_id, scheduled_at)` unique constraint, (d) optionally Kafka transactions (`transactional.id` per executor) for exactly-once. The alternative is a single leader watcher elected through ZooKeeper.
10. **Backfill:** on watcher restart, find overdue `scheduled` jobs. Create JobRuns with `scheduled_at = missed_time` and status `backfill`, and publish at a throttled **100 jobs/sec**. Cron jobs can use a "run all missed" or "only the latest" policy. A per-job SLA can mark runs `missed_sla` and skip them. Notify users.

## 8. Design rules (do not violate)

- **At-least-once plus idempotent jobs.** Never assume a job runs exactly once. Jobs must tolerate re-execution (check state, upsert instead of insert).
- **UTC everywhere** in storage and in the watcher. Convert at the API and UI edges. Recompute `next_run_time` across DST changes. A nonexistent local time (e.g. 2 AM on DST-start day) follows a configurable policy.
- **Batch the watcher** (1000 jobs per query, batched Kafka publishes). Never fetch one row per query.
- **Poll interval ≥ 10s.** 20s is the default. Meet the ±2s target with look-ahead, not faster polling.
- **Exponential backoff always includes jitter**, to avoid a thundering herd.
- **Logs go to object storage**, not the DB. The DB keeps only metadata and `logs_url`.
- **Run DAG cycle detection** (DFS) on every create and update.
- **Priority:** separate Kafka topics per tier (high 8–10 / medium 5–7 / low 1–4) with 50/30/20 consumers. Aging boosts any job that has waited more than 1 hour.
- Other techniques: horizontal executor scaling, read replicas for history, watcher sharding by priority or interval, a circuit breaker (pause a job for 10 min after more than 5 external failures), executor worker pools (10 threads × 100 executors = 1000 concurrent jobs).

## 9. Monitoring and alerts

- Metrics (Prometheus or CloudWatch, shown in Grafana): job success/failure counts, duration, queue depth per priority, executors online/offline.
- Alerts:
  - Critical: a single job fails more than 50% of its runs (e.g. 6 of the last 10).
  - Warning: the system-wide failure rate exceeds 10% in 1h.
  - DLQ has more than 100 messages.
  - Fewer than 30% of executors are healthy, or executor capacity is above 80%.
  - An SLA is breached.
- Channels: PagerDuty for critical, Slack for warnings, email for the daily digest.

## 10. Key numbers

- Watcher: polls every 20s, up to 1000 jobs per poll. Redis lock about 10ms per job. Batch Kafka publish about 50ms. Consumer to executor about 100ms.
- Kafka: about 10K msgs/sec across topics, 7-day retention.
- Heartbeat 10s. Health check 30s. Dead after 60s. Lock TTL 60s. Job cache TTL 5 min.
- Retries: 3 by default (4 attempts in total), with backoff of 60, 120 and 240s plus jitter. Timeout defaults to 3600s. Cancel grace period is 30s.
- Backfill: 100 jobs/sec. Logs: 100K jobs/day × 10KB is about 1GB/day, which is why logs stay out of the DB.

## 11. Alternatives considered

- **Event-driven (AWS EventBridge plus Lambda or SQS):** serverless with no polling, but it brings vendor lock-in, a 300-target limit and cold starts.
- **Managed orchestration (Step Functions, Temporal):** durable execution and built-in retries, but it costs more per transition and gives less control.
- **Delay queues (RabbitMQ, SQS):** simple for one-off delays, but SQS caps delay at 15 min, there is no cron support, and recurring jobs fit poorly.
- **Redis Sorted Set:** use the run timestamp as the score and `ZRANGEBYSCORE` to find due jobs (from the diagram).

## 12. Inconsistencies in the source (resolve during implementation)

1. **Backoff formula:** Step 5 says `base * 2^attempt`, but its own numbers (60/120/240) match `base * 2^(attempt-1)`, which the Q&A also uses. **Use `2^(attempt-1)`.**
2. **Latency:** the NFR says ±2s, but the worked example totals about 20s, because a 20s poll means a due job can wait up to one interval. **Fix: a look-ahead window (`[now, now+5min]`, from the diagram) plus precise dispatch at the scheduled time.**
3. **The `jobs.status` enum is missing states the text uses:** `failed_permanently` and `queued` (diagram). `job_runs.status` is missing `backfill`, `missed_sla` and `queued`. Extend both enums.
4. **Cancellation transport:** the cheat sheet uses a Kafka `cancel` topic, while the diagram uses a Redis key with a TTL (`job:1232, request: cancel`). Pick one, or use Kafka for push and Redis as a polled fallback.
5. **Scale:** the diagram says 10K jobs/sec and the cheat sheet says "1000s/sec". Design for 10K.
6. **DAG:** out of scope in the video, in scope in the cheat sheet. Build it as a later phase.
7. **The idempotency key `(job_id, scheduled_at)`** appears only in the Q&A. Add it to the schema explicitly.

## 13. Implementation guardrails

- **Executors run user-supplied code** (scripts, shell commands, HTTP calls). Always sandbox them: a container or separate process, resource limits, no host secrets, network policy. Never pass payload fields to a shell without escaping.
- Use parameterized SQL only. Validate cron expressions, time zones and payload size at the API boundary.
- No hardcoded credentials. Read configuration and secrets from the environment or a secret manager.
- AuthN/AuthZ and rate limiting live at the gateway. Every job is scoped by `owner_id`, so users can see and cancel only their own jobs.

## 14. Open decisions

Resolved: language and framework (Java + Spring Boot), stores (Postgres + Kafka + Redis), job type (HTTP API calls only), DAG (in scope), scale target (50K/s), and service layout (monorepo, four microservices with one image each, built from the shared Dockerfile).

Also resolved in v0.2: internal targets only, synchronous HTTP, the endpoint registry, DAG data passing, retries and DLQ, and GKE deployment.

Nothing is open. All HLD review questions were resolved on 2026-10-12 (now reflected in HLD section 14).
