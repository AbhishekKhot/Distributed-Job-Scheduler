# Kairos: Low-Level Design

## 1. Conventions

| Topic | Rule |
|---|---|
| Ids | UUIDv7 (time-ordered). Job, workflow, run, workflow-run and DLQ ids carry their shard number (0–255) in the low 8 bits, so any id routes to its cell database. |
| Shard | `murmur3(team_id + name) mod 256` for jobs and workflows; runs inherit the shard of their job or workflow |
| Time | `timestamptz`, stored in UTC; API timestamps are RFC 3339 in UTC; cron schedules are evaluated in the job's IANA time zone |
| Team scope | Every team-owned table has `team_id`; the API filters every read and write by the caller's team |
| Retention | 7 days for `run_history`, `task_outputs`, resolved `dead_letters` and `DELETED` jobs/workflows; 90 days for `audit_log` |

## 2. Database design

Two kinds of PostgreSQL database, both using schema `scheduler`:
- **control database** (one): teams, users, credentials, endpoints;
- **cell database** (two in production; cell 0 holds shards 0–127, cell 1 holds 128–255): definitions, schedules, runs and everything they produce.

References from cell tables to control tables (`team_id`, `endpoint_id`) are checked by the application, because they cross databases.

### 2.1 Entity relationships

| Parent | Child | Cardinality | Link |
|---|---|---|---|
| `teams` | `users` | 1 to 0..n | `users.team_id` (nullable: a user may have no team yet) |
| `users` | `user_sessions` | 1 to 0..n | `user_sessions.user_id` |
| `teams` | `api_keys`, `team_secrets`, `team_ca_bundles`, `team_keysets` | 1 to 0..n | `team_id` |
| `teams` | `api_endpoints` | 1 to 0..n | `api_endpoints.team_id` |
| `api_endpoints` | `api_endpoint_versions` | 1 to 1..n | `endpoint_id` |
| `teams` (control) | `jobs`, `workflows` (cell) | 1 to 0..n | `team_id` |
| `api_endpoints` (control) | `jobs` (cell) | 1 to 0..n | `jobs.endpoint_id` |
| `jobs` / `workflows` | `job_versions` / `workflow_versions` | 1 to 1..n | `job_id` / `workflow_id` |
| `jobs` / `workflows` | `schedules` | 1 to 0..1 | `schedules.schedule_id` = job or workflow id |
| `jobs` | `run_inflight`, `run_history`, `dead_letters` | 1 to 0..n | `definition_id` |
| `workflows` | `workflow_runs` | 1 to 0..n | `workflow_runs.workflow_id` |
| `workflow_runs` | `run_inflight` (tasks), `task_outputs` | 1 to 1..n | `target_id` / `workflow_run_id` |
| `run_inflight` | `outbox` | 1 to 0..n | `outbox.run_id` |
| `jobs` | `job_health` | 1 to 0..1 | `job_id` |

### 2.2 Control database

**teams**

| Column | Type | Notes |
|---|---|---|
| team_id | uuid | PK |
| slug | text | Unique; `^[a-z][a-z0-9-]{2,62}$`, for example `payments` |
| quotas | jsonb | `maxJobs`, `maxExecPerSec`, `maxInflight`, `maxWorkflowTasks`, `backfillPerSec`, `redrivePerSec` |
| retry_default | jsonb | Team-level retry policy, nullable |
| created_at, updated_at | timestamptz | |

**users**

| Column | Type | Notes |
|---|---|---|
| user_id | uuid | PK |
| team_id | uuid | FK → teams, nullable |
| email | text | Unique, lower-case, ≤ 254 |
| password_hash | text | Argon2id PHC string |
| status | text | `ACTIVE`, `DISABLED` |
| failed_logins | smallint | Reset on successful login |
| locked_until | timestamptz | Progressive lockout |
| password_changed_at, last_login_at, created_at, updated_at | timestamptz | |

**user_sessions** (one row per refresh token; rotation adds a row to the same family)

| Column | Type | Notes |
|---|---|---|
| session_id | uuid | PK |
| user_id | uuid | FK → users |
| family_id | uuid | All rotations of one login |
| token_hash | bytea | SHA-256 of the refresh token, unique |
| status | text | `ACTIVE`, `ROTATED`, `REVOKED` |
| created_at, expires_at, last_used_at | timestamptz | |
| user_agent | text | ≤ 256 |
| ip_address | inet | |

**api_keys**

| Column | Type | Notes |
|---|---|---|
| key_id | text | PK, 16 alphanumeric characters (the public part of the key) |
| team_id | uuid | FK → teams |
| name | text | 1–64 |
| secret_hash | bytea | HMAC-SHA256(pepper, secret) |
| pepper_version | smallint | |
| created_by | uuid | FK → users |
| created_at, expires_at, revoked_at, last_used_at | timestamptz | |

**team_keysets** (PK `team_id, purpose`): `team_id` uuid, `purpose` text (`SECRETS`, `OUTPUTS`, `SIGNING`), `encrypted_keyset` bytea (sealed by Cloud KMS), `kek_uri` text, `updated_at` timestamptz.

**team_secrets** (PK `team_id, name, version`): `team_id` uuid, `name` text, `version` int, `kind` text (`BASIC`, `BEARER`, `API_KEY`, `OAUTH2_CLIENT`), `ciphertext` bytea (≤ 16 KB), `created_at` timestamptz, `disabled_at` timestamptz.

**team_ca_bundles** (PK `team_id, name`): `team_id` uuid, `name` text, `pem` text (≤ 64 KB), `created_at` timestamptz.

**api_endpoints**

| Column | Type | Notes |
|---|---|---|
| endpoint_id | uuid | PK |
| team_id | uuid | FK → teams; unique with `name` |
| name | text | `^[a-z][a-z0-9-]{1,62}$` |
| version | int | Current version |
| status | text | `ACTIVE`, `SUSPENDED`, `DISABLED` |
| status_reason | text | For example `AUTO_SUSPENDED` |
| status_changed_at | timestamptz | |
| spec | jsonb | Validated endpoint configuration (section 3.4), ≤ 64 KB |
| created_at, updated_at | timestamptz | |

**api_endpoint_versions** (PK `endpoint_id, version`, immutable, pinned by runs): `endpoint_id` uuid, `version` int, `spec` jsonb, `created_at` timestamptz, `created_by` text.

**platform_keysets** (PK `purpose`): `purpose` text (`JWT_SIGNING`), `encrypted_keyset` bytea, `kek_uri` text, `rotated_at` timestamptz.

**api_idempotency** (PK `team_id, idem_key`; also present in each cell for resources stored there): `team_id` uuid, `idem_key` text (≤ 64), `request_hash` bytea, `status` smallint (1 in progress, 2 done), `http_status` smallint, `response_body` jsonb, `created_at` timestamptz.

**audit_log** (partitioned by day): `audit_id` uuid, `at` timestamptz, `team_id` uuid, `actor` text (`user:{id}`, `key:{keyId}`, `operator:{command}`, `system:{task}`), `action` text, `resource_type` text, `resource_id` text, `details` jsonb (never secrets).

**cell_map** (PK `cell_id`): `cell_id` smallint, `shard_from` smallint, `shard_to` smallint, `datasource_ref` text, `status` text (`ACTIVE`, `DRAINING`, `READ_ONLY`).

**scheduler_nodes** (PK `node_id`): `node_id` text (`{service}-{pod}-{startMillis}`), `roles` text, `version` text, `started_at` timestamptz, `heartbeat_at` timestamptz.

**scheduler_flags** (PK `name`): `name` text (`redis_marker_quarantine_until`), `value_ts` timestamptz, `updated_at` timestamptz.

### 2.3 Cell database

**jobs**

| Column | Type | Notes |
|---|---|---|
| job_id | uuid | PK, shard in the low 8 bits |
| team_id | uuid | Unique with `name` among non-deleted jobs |
| name | text | ≤ 128, cannot be changed |
| version | int | Current version |
| status | text | `ACTIVE`, `PAUSED`, `AUTO_PAUSED`, `COMPLETED`, `FAILED`, `CANCELLED`, `DELETED` |
| endpoint_id | uuid | Endpoint in the control database |
| spec | jsonb | Validated job definition (section 3.5), ≤ 128 KB |
| created_by | text | `user:{id}` or `key:{keyId}` |
| created_at, updated_at | timestamptz | |

**job_versions** (PK `job_id, version`, immutable): `job_id` uuid, `version` int, `spec` jsonb, `created_at` timestamptz.

**workflows**: same shape as `jobs` without `endpoint_id`; `workflow_id` uuid PK; `status` `ACTIVE`, `PAUSED`, `CANCELLED`, `DELETED`; `spec` jsonb ≤ 512 KB (tasks, edges and the computed plan).

**workflow_versions** (PK `workflow_id, version`, immutable): `workflow_id` uuid, `version` int, `spec` jsonb, `created_at` timestamptz.

**schedules** (one narrow row per active job or workflow)

| Column | Type | Notes |
|---|---|---|
| schedule_id | uuid | PK = job or workflow id |
| target_type | smallint | 1 job, 3 workflow |
| team_id | uuid | |
| endpoint_id | uuid | Jobs only; used to hold schedules of a suspended endpoint |
| shard_id | smallint | 0–255 |
| status | smallint | 1 active, 2 paused, 3 held by suspended endpoint |
| next_fire_at | timestamptz | Indexed with `shard_id` for the fire poller |
| kind | smallint | 1 one-time, 2 cron, 3 interval |
| cron_expr | text | 5-field cron |
| timezone | text | IANA zone |
| interval_ms | bigint | ≥ 1000 |
| spread_ms | int | Fixed per-job offset to smooth top-of-minute spikes |
| dst_policy | smallint | Gap and overlap handling |
| misfire_policy | smallint | 1 `RUN_ALL`, 2 `RUN_LATEST`, 3 `SKIP` |
| max_catchup | smallint | Default 100 |
| definition_version | int | Version to fire |
| priority | smallint | 1–10 |
| last_fired_local | timestamp | Last fired local time, for DST-safe cron iteration |

**run_inflight** (every unfinished run; hash-partitioned by shard)

| Column | Type | Notes |
|---|---|---|
| shard_id | smallint | PK part |
| run_id | uuid | PK part; also the `Idempotency-Key` sent to the target |
| team_id | uuid | |
| target_type | smallint | 1 job, 2 workflow task |
| definition_id | uuid | Job or workflow id |
| definition_version | int | Pinned |
| target_id | uuid | Job id, or workflow run id for tasks |
| task_key | text | Workflow task key, empty for jobs |
| endpoint_id, endpoint_version | uuid, int | Pinned endpoint version |
| scheduled_at | timestamptz | Occurrence time; unique with `target_id, task_key` |
| due_at | timestamptz | When to dispatch (spread, retry delay, backfill pacing) |
| state | smallint | 1 `WAITING`, 2 `QUEUED`, 3 `RUNNING`, 4 `DELAYED` |
| attempt | smallint | Starts at 1 |
| series_start, series_started_at | smallint, timestamptz | Start of the current retry series (max-age anchor) |
| infra_attempt | smallint | Re-queues after executor loss, max 3 |
| deferrals | int | Rate-limit or breaker deferrals |
| fence | int | Increases on every dispatch and claim |
| owner_node | text | Executor holding the run |
| lease_until | timestamptz | Executor lease |
| remaining_deps | smallint | Workflow tasks: unfinished upstream tasks |
| cancel_requested | boolean | |
| priority | smallint | 1–10 |
| trigger | smallint | 1 schedule, 2 immediate, 3 manual, 4 backfill, 5 redrive |
| max_lateness_ms | int | Skip if it would start later than this |

**outbox** (messages waiting to be published; hash-partitioned by shard)

| Column | Type | Notes |
|---|---|---|
| shard_id | smallint | PK part |
| outbox_id | uuid | PK part, time-ordered |
| topic | smallint | 1 `run.high`, 2 `run.medium`, 3 `run.low`, 4 `run.cancel`, 5 `run.completed`, 6 `run.dead` |
| msg_key | bytea | Kafka key |
| payload | bytea | Protobuf, ≤ 256 KB, never secrets |
| trace_parent | text | W3C trace context |
| run_id | uuid | For dispatch and cancel rows |
| available_at | timestamptz | Publish no earlier than this |
| created_at | timestamptz | |

**run_history** (one row per finished attempt; append-only; partitioned by day on `scheduled_at`)

| Column | Type | Notes |
|---|---|---|
| scheduled_at, run_id, attempt, infra_attempt | timestamptz, uuid, smallint, smallint | PK |
| team_id, target_type, definition_id, target_id, task_key, endpoint_id, trigger | | As in `run_inflight` |
| outcome | smallint | 1 `SUCCEEDED`, 2 `FAILED_RETRYING`, 3 `DEAD_LETTERED`, 4 `CANCELLED`, 5 `SKIPPED`, 6 `UPSTREAM_FAILED`, 7 `EXECUTOR_LOST` |
| error_class | smallint | For example `TIMEOUT`, `HTTP_5XX`, `HTTP_4XX`, `UNKNOWN_OUTCOME` |
| final | boolean | Last attempt of the run |
| http_status | smallint | |
| started_at, finished_at | timestamptz | |
| latency_ms | int | |
| executor_id | text | |
| error | text | Redacted, ≤ 512 |
| response_snippet | text | Redacted, ≤ 1 KB |

**workflow_runs**

| Column | Type | Notes |
|---|---|---|
| workflow_run_id | uuid | PK |
| workflow_id, workflow_version | uuid, int | Unique with `scheduled_at` |
| team_id | uuid | |
| shard_id | smallint | |
| scheduled_at | timestamptz | |
| trigger | smallint | |
| status | smallint | 1 `RUNNING`, 2 `BLOCKED`, 3 `SUCCEEDED`, 4 `FAILED`, 5 `CANCELLED` |
| total, succeeded, failed, cancelled, skipped | smallint | Task counters |
| blocked_until | timestamptz | Review deadline when `BLOCKED` |
| started_at, finished_at | timestamptz | |

**task_outputs** (PK `scheduled_at, workflow_run_id, task_key`; partitioned by day): `team_id` uuid, `outputs` jsonb (non-sensitive values), `sealed` bytea (encrypted sensitive values), `created_at` timestamptz; ≤ 24 KB per task.

**dead_letters**

| Column | Type | Notes |
|---|---|---|
| dlq_id | uuid | PK |
| team_id, run_id, shard_id, scheduled_at | uuid, uuid, smallint, timestamptz | |
| target_type, definition_id, definition_version, target_id, task_key | | Which job or task |
| endpoint_id, endpoint_version | uuid, int | |
| attempt, infra_attempt, priority | smallint | |
| reason | smallint | `error_class` |
| last_http_status | smallint | |
| last_error, response_snippet | text | Redacted |
| status | smallint | 1 `OPEN`, 2 `ACKNOWLEDGED`, 3 `REDRIVEN`, 4 `DISCARDED` |
| assignee, notes, resolved_by | text | |
| resolved_at | timestamptz | |
| redrive_mode | smallint | 1 original configuration, 2 current |
| created_at | timestamptz | |

**job_health** (only for jobs with recent failures): `job_id` uuid PK, `team_id` uuid, `recent_mask` smallint (last 10 outcomes as bits), `recent_count` smallint, `consecutive_dead` smallint, `unhealthy_since` timestamptz, `updated_at` timestamptz.

**endpoint_health_minute** (PK `endpoint_id, minute, node_id`): `team_id` uuid, `attempts`, `successes`, `retryable`, `non_retryable`, `dead_lettered` int, `latency_sum_ms` bigint, `latency_max_ms` int.

**team_stats_minute** (PK `team_id, minute, node_id`): `attempts`, `successes`, `dead_lettered` int, `latency_sum_ms` bigint.

**shard_leases** (one row per shard of the cell): `shard_id` smallint PK, `owner_node` text, `generation` bigint, `lease_until` timestamptz.

## 3. APIs

### 3.1 Common contract

| Item | Contract |
|---|---|
| Base URL | `https://kairos.<internal-domain>/v1` |
| Authentication | `Authorization: Bearer <accessToken>` or `Authorization: Bearer <apiKey>` on every endpoint except signup, login, refresh and JWKS |
| Request headers | `Content-Type: application/json`; `Idempotency-Key: <1–64 chars>` (optional, on creates marked *idempotent*); `If-Match: "<version>"` (required on `PUT`); `traceparent` (optional) |
| Response headers | `Content-Type: application/json` (or `application/problem+json` for errors); `ETag: "<version>"` on single resources; `Location` on `201`; `X-Request-Id`; `Retry-After` on `429` and `503` |
| Errors | RFC 9457: `{"type", "title", "status", "detail", "instance", "traceId", "errors": [{"field", "code"}]}`. Unknown fields in a request are rejected with `400`. |
| Common statuses | `400` validation, `401` not authenticated, `403` `no-team` (user without a team), `404` not found or another team's resource, `409` conflict, `412` version mismatch, `429` rate limit or quota |
| Pagination | `?limit=1..200` (default 50) and `?cursor=<opaque>`; responses are `{"items": [...], "nextCursor": "..." }` |
| Team | Always taken from the credential; never sent in the path or body |

### 3.2 API list (68 APIs)

| Group | Count | APIs |
|---|---|---|
| Accounts | 7 | signup, login, refresh, logout, get profile, change password, JWKS |
| Team | 17 | create team, get team, list/add/remove members, create/list/revoke API keys, create/list/update/delete secrets, add/list/delete CA bundles, rotate signing key, audit log |
| Endpoints | 9 | create, list, get, update, delete, test, suspend, resume, health |
| Jobs | 12 | create, batch create, list, get, update, delete, pause, resume, cancel, run now, backfill, list runs |
| Workflows | 10 | create, list, get, update, delete, pause, resume, cancel, run now, list workflow runs |
| Workflow runs | 3 | get, cancel, retry |
| Runs | 3 | list, get, cancel |
| DLQ | 6 | list, get, acknowledge, redrive, discard, bulk redrive |
| Stats | 1 | team stats |

### 3.3 Accounts and team

| # | API | Request | Response | Background effect |
|---|---|---|---|---|
| 1 | `POST /v1/auth/signup` | `{"email", "password"}` (12–128 chars) | `201 {"userId", "email", "createdAt"}`; `409 email-taken` | None |
| 2 | `POST /v1/auth/login` | `{"email", "password"}` | `200 {"accessToken", "tokenType": "Bearer", "expiresIn": 900, "refreshToken", "refreshExpiresIn": 2592000, "teamId": null}`; `401` generic | None |
| 3 | `POST /v1/auth/refresh` | `{"refreshToken"}` | `200` same as login, with the current `teamId`; `401` (reuse revokes the session family) | None |
| 4 | `POST /v1/auth/logout` | `{"refreshToken"}` | `204` | None |
| 5 | `GET /v1/me` | — | `200 {"userId", "email", "teamId", "teamName", "createdAt"}` | None |
| 6 | `POST /v1/me/password` | `{"currentPassword", "newPassword"}` | `204` | Revokes the user's other sessions |
| 7 | `GET /.well-known/jwks.json` | — | `200 {"keys": [ES256 public keys]}` | None |
| 8 | `POST /v1/teams` | `{"name": "payments"}` | `201 {"teamId", "name", "quotas", "signingKey", "createdAt"}` (`signingKey` shown once); `409 already-in-team`, `409 team-name-taken` | Creates the team's encryption and signing keysets |
| 9 | `GET /v1/team` | — | `200 {"teamId", "name", "quotas", "usage": {"jobs", "execPerSec", "inflight"}, "createdAt"}` | None |
| 10 | `GET /v1/team/members` | — | `200 {"items": [{"userId", "email", "addedAt"}]}` | None |
| 11 | `POST /v1/team/members` | `{"email"}` | `201 {"userId", "email"}`; `404 user-not-found`; `409 user-in-another-team` | None |
| 12 | `DELETE /v1/team/members/{userId}` | — | `204`; `409 last-member` | Revokes the removed user's sessions |
| 13 | `POST /v1/api-keys` | `{"name", "expiresAt"?}` | `201 {"keyId", "name", "key", "expiresAt", "createdAt"}` (`key` shown once, format `sk_{env}_{keyId}_{secret}`) | None |
| 14 | `GET /v1/api-keys` | — | `200 {"items": [{"keyId", "name", "createdBy", "expiresAt", "lastUsedAt", "revokedAt"}]}` | None |
| 15 | `DELETE /v1/api-keys/{keyId}` | — | `204` | Key cache expires within 60s |
| 16 | `POST /v1/secrets` | `{"name", "kind": "BEARER", "value"}` | `201 {"name", "kind", "version", "createdAt"}` (value never returned) | None |
| 17 | `GET /v1/secrets` | — | `200 {"items": [{"name", "kind", "version", "createdAt"}]}` | None |
| 18 | `PUT /v1/secrets/{name}` | `{"value"}` | `200 {"name", "version"}` | New calls use the new version |
| 19 | `DELETE /v1/secrets/{name}` | — | `204`; `409` while an endpoint uses it | None |
| 20 | `POST /v1/ca-bundles` | `{"name", "pem"}` | `201 {"name", "createdAt"}` | None |
| 21 | `GET /v1/ca-bundles` | — | `200 {"items": [{"name", "createdAt"}]}` | None |
| 22 | `DELETE /v1/ca-bundles/{name}` | — | `204`; `409` while an endpoint uses it | None |
| 23 | `POST /v1/team/signing-key:rotate` | — | `200 {"signingKey", "keyVersion"}` (previous key accepted for 24h) | None |
| 24 | `GET /v1/audit-log` | `?from&to&action&cursor` | `200 {"items": [{"at", "actor", "action", "resourceType", "resourceId", "details"}]}` | None |

### 3.4 Endpoints

**Endpoint body** (create and update):

```json
{
  "name": "orders-create",
  "method": "POST",
  "urlTemplate": "https://orders.payments.svc.cluster.local:8443/v1/customers/{customerId}/orders",
  "defaultHeaders": { "X-Source": "kairos" },
  "auth": { "type": "BEARER", "secretRef": "orders-api-token" },
  "caBundle": null,
  "timeoutMs": 10000,
  "successStatus": ["2xx"],
  "statusOverrides": { "409": "SUCCESS" },
  "retryPolicy": { "maxRetries": 5, "strategy": "EXPONENTIAL_JITTER", "baseDelaySec": 30, "maxDelaySec": 600, "maxAgeSec": 86400 },
  "limits": { "maxConcurrency": 200, "ratePerSec": 1000 },
  "outputs": { "orderId": { "from": "BODY", "path": "$.data.id", "required": true, "sensitive": false } },
  "unknownOutcomePolicy": "RETRY_WITH_IDEMPOTENCY_KEY",
  "suspendPolicy": { "enabled": true }
}
```

Field rules: `auth.type` is `NONE`, `BASIC`, `BEARER`, `API_KEY` or `OAUTH2_CLIENT_CREDENTIALS`; `timeoutMs` 1000–300000 (default 30000); `strategy` is `EXPONENTIAL_JITTER`, `FULL_JITTER` or `FIXED`; the URL must be HTTPS and pass the platform target policy; credentials only through `secretRef`.

**Endpoint resource:** the body above plus `{"id", "status", "statusReason", "version", "createdAt", "updatedAt"}`.

| # | API | Request | Response | Background effect |
|---|---|---|---|---|
| 25 | `POST /v1/endpoints` | Endpoint body | `201` endpoint resource; `400 target-not-allowed` | None |
| 26 | `GET /v1/endpoints` | `?status&cursor` | `200 {"items": [endpoint]}` | None |
| 27 | `GET /v1/endpoints/{id}` | — | `200` endpoint resource | None |
| 28 | `PUT /v1/endpoints/{id}` | Endpoint body, `If-Match` | `200` endpoint resource (new version) | Jobs use the new version within 5s; running workflow runs keep their pinned version |
| 29 | `DELETE /v1/endpoints/{id}` | — | `204`; `409` while jobs reference it | None |
| 30 | `POST /v1/endpoints/{id}:test` | `{"pathParams", "query", "headers", "body"}` | `200 {"httpStatus", "latencyMs", "outcome", "outputs"}` (sensitive values redacted) | One synchronous call from an executor |
| 31 | `POST /v1/endpoints/{id}/suspend` | `{"reason"}` | `200` endpoint resource | Watcher holds its schedules; state processor defers its retries |
| 32 | `POST /v1/endpoints/{id}/resume` | — | `200` endpoint resource | Held schedules fire again; missed fires follow each job's misfire policy |
| 33 | `GET /v1/endpoints/{id}/health` | `?window=5m..24h` | `200 {"attempts", "successes", "failureRatio", "p95LatencyMs", "deadLettered", "breaker"}` | None |

### 3.5 Jobs

**Job body** (create and update):

```json
{
  "name": "nightly-order-sync",
  "schedule": { "type": "CRON", "expression": "0 2 * * *", "timezone": "Asia/Kolkata", "spreadSec": 0 },
  "endpoint": "orders-create",
  "request": { "pathParams": { "customerId": "42" }, "query": {}, "headers": {}, "body": { "mode": "sync" } },
  "priority": 7,
  "retryPolicy": null,
  "misfirePolicy": "RUN_LATEST",
  "maxCatchup": 100,
  "maxLatenessSec": null,
  "autoPauseAfter": 3
}
```

`schedule.type` is `IMMEDIATE`, `ONE_TIME` (with `at`), `CRON` (with `expression` and `timezone`) or `INTERVAL` (with `everySec`). `priority` 1–10: 8–10 high, 5–7 medium, 1–4 low tier.

**Job resource:** the body plus `{"id", "status", "version", "nextFireAt", "lastRun": {"runId", "status", "finishedAt"}, "createdAt", "updatedAt"}`.

| # | API | Request | Response | Background effect |
|---|---|---|---|---|
| 34 | `POST /v1/jobs` *idempotent* | Job body | `201` job resource; `429` over `maxJobs` | Creates the schedule; the fire poller fires it when due. `IMMEDIATE` inserts a run and outbox row at once. |
| 35 | `POST /v1/jobs:batch` *idempotent* | `{"jobs": [job body, …]}` (≤ 1K) | `207 {"results": [{"index", "status", "id" or "error"}]}` | As above, per job |
| 36 | `GET /v1/jobs` | `?status&endpoint&name&cursor` | `200 {"items": [job]}` (deleted jobs excluded) | None |
| 37 | `GET /v1/jobs/{id}` | — | `200` job resource | None |
| 38 | `PUT /v1/jobs/{id}` | Job body, `If-Match` | `200` job resource | `next_fire_at` recomputed; queued runs keep their version |
| 39 | `DELETE /v1/jobs/{id}` | — | `204` | Schedule removed; waiting, queued and delayed runs cancelled; running runs get a cancel broadcast; purged after 7 days |
| 40 | `POST /v1/jobs/{id}/pause` | — | `200` job resource | Watcher stops firing it |
| 41 | `POST /v1/jobs/{id}/resume` | — | `200` job resource | Next fire computed; misfire policy applies to missed fires |
| 42 | `POST /v1/jobs/{id}/cancel` | — | `200` job resource (`CANCELLED`) | Like delete, but the job stays visible |
| 43 | `POST /v1/jobs/{id}/runnow` *idempotent* | `{"request"?}` (one-off overrides) | `202 {"runId"}` | Run and outbox row inserted; relay publishes within ~100 ms |
| 44 | `POST /v1/jobs/{id}/backfill` | `{"from", "to", "dryRun"}` | `202 {"occurrences", "firstAt", "lastAt"}` | `DELAYED` runs paced at 100/s by the delayed poller |
| 45 | `GET /v1/jobs/{id}/runs` | `?status&from&to&cursor` | `200 {"items": [run]}` | None |

### 3.6 Workflows and workflow runs

**Workflow body:**

```json
{
  "name": "order-fulfilment",
  "schedule": { "type": "CRON", "expression": "*/15 * * * *", "timezone": "Asia/Kolkata" },
  "priority": 5,
  "onTaskFailure": "BLOCK_FOR_REVIEW",
  "reviewTimeoutSec": 86400,
  "tasks": {
    "login":       { "endpoint": "auth-service-token" },
    "createOrder": { "endpoint": "orders-create", "dependsOn": ["login"],
                     "request": { "headers": { "Authorization": "Bearer {{ tasks.login.outputs.accessToken }}" },
                                  "body": { "runRef": "{{ run.id }}" } } },
    "notify":      { "endpoint": "notify-send", "dependsOn": ["createOrder"],
                     "request": { "body": { "orderId": "{{ tasks.createOrder.outputs.orderId }}" } } }
  }
}
```

Rules: acyclic, ≤ 200 tasks, ≤ 10 levels; placeholders may reference only declared outputs of ancestor tasks and never the URL host; `onTaskFailure` is `BLOCK_FOR_REVIEW` or `FAIL_FAST`.

**Workflow run resource:** `{"workflowRunId", "workflowId", "version", "status", "scheduledAt", "startedAt", "finishedAt", "counts": {"total", "succeeded", "failed", "cancelled", "skipped"}, "tasks": [{"taskKey", "runId", "status", "attempt", "dependsOn"}]}`.

| # | API | Request | Response | Background effect |
|---|---|---|---|---|
| 46 | `POST /v1/workflows` *idempotent* | Workflow body | `201` workflow resource | Schedule created; on fire, one workflow run plus one run per task (roots `QUEUED`, others `WAITING`) |
| 47 | `GET /v1/workflows` | `?status&cursor` | `200 {"items": [workflow]}` | None |
| 48 | `GET /v1/workflows/{id}` | — | `200` workflow resource | None |
| 49 | `PUT /v1/workflows/{id}` | Workflow body, `If-Match` | `200` workflow resource | Running workflow runs keep their pinned version |
| 50 | `DELETE /v1/workflows/{id}` | — | `204` | Schedule removed; open workflow runs cancelled |
| 51 | `POST /v1/workflows/{id}/pause` | — | `200` | Watcher stops firing it |
| 52 | `POST /v1/workflows/{id}/resume` | — | `200` | Misfire policy applies |
| 53 | `POST /v1/workflows/{id}/cancel` | — | `200` | Open workflow runs cancelled |
| 54 | `POST /v1/workflows/{id}/runnow` *idempotent* | — | `202 {"workflowRunId"}` | Workflow run and root task dispatches written |
| 55 | `GET /v1/workflows/{id}/runs` | `?status&from&to&cursor` | `200 {"items": [workflow run summary]}` | None |
| 56 | `GET /v1/workflow-runs/{id}` | — | `200` workflow run resource | None |
| 57 | `POST /v1/workflow-runs/{id}/cancel` | — | `200` | Live tasks cancelled; running ones get a cancel broadcast |
| 58 | `POST /v1/workflow-runs/{id}/retry` | `{"from": "FAILED_TASKS"}` | `202` | Failed tasks re-queued with succeeded tasks' outputs reused |

### 3.7 Runs, DLQ and stats

**Run resource:** `{"runId", "jobId", "workflowRunId", "taskKey", "status", "attempt", "trigger", "scheduledAt", "startedAt", "finishedAt", "httpStatus", "latencyMs", "errorClass", "error", "attempts": [{"attempt", "outcome", "httpStatus", "latencyMs", "finishedAt"}]}`. Live statuses come from `run_inflight` (`WAITING`, `QUEUED`, `RUNNING`, `DELAYED`); final ones from `run_history`.

**DLQ entry:** `{"dlqId", "runId", "jobId", "workflowRunId", "taskKey", "endpoint", "reason", "lastHttpStatus", "lastError", "attempt", "status", "assignee", "notes", "createdAt", "resolvedAt"}`.

| # | API | Request | Response | Background effect |
|---|---|---|---|---|
| 59 | `GET /v1/runs` | `?status&job&endpoint&from&to&cursor` | `200 {"items": [run]}` | None (reads all cells in parallel) |
| 60 | `GET /v1/runs/{runId}` | — | `200` run resource | None |
| 61 | `POST /v1/runs/{runId}/cancel` | — | `200` run resource; `409` if already final | Queued: run and unpublished outbox row deleted. Running: cancel broadcast on `kairos.run.cancel`; the executor aborts the call. |
| 62 | `GET /v1/dlq` | `?status&endpoint&reason&cursor` | `200 {"items": [dlq entry]}` | None |
| 63 | `GET /v1/dlq/{id}` | — | `200` DLQ entry | None |
| 64 | `POST /v1/dlq/{id}/acknowledge` | `{"assignee"?, "notes"?}` | `200` DLQ entry | None |
| 65 | `POST /v1/dlq/{id}/redrive` | `{"mode": "ORIGINAL" or "CURRENT"}` | `202` DLQ entry (`REDRIVEN`) | New attempt series for the same run id, so the target sees the same `Idempotency-Key` |
| 66 | `POST /v1/dlq/{id}/discard` | `{"reason"}` (required) | `200` DLQ entry (`DISCARDED`) | Blocked workflow dependents are failed |
| 67 | `POST /v1/dlq:redrive` *idempotent* | `{"filter": {"endpoint", "reason", "from", "to"}, "mode", "dryRun"}` | `202 {"matched", "scheduled"}` | Redrives paced at 100/s by the delayed poller |
| 68 | `GET /v1/stats` | `?window=1h..7d` | `200 {"attempts", "successRate", "p50LatencyMs", "p95LatencyMs", "deadLettered", "openDlq", "byEndpoint": [...]}` | None |

## 4. Background processing

### 4.1 Jobs and parameters

| Job | Service | Runs | Parameters (defaults) | What it does |
|---|---|---|---|---|
| Shard lease manager | kairos-watcher | Every 5s | Lease TTL 15s; fair share `ceil(256 / watchers)`; release surplus above fair share + 2 | Acquires, renews and releases shard leases in each cell |
| Fire poller | kairos-watcher | Every 1s ±20% per group of 8 shards, and on `NOTIFY` | Look-ahead 5s; ≤ 250 schedules per shard per cycle; misfire threshold 60s; pauses while the oldest due outbox row is > 10s old | Claims due schedules; inserts runs (and workflow runs) with outbox rows; advances `next_fire_at` |
| Delayed poller | kairos-watcher | Every 1s | ≤ 250 runs per shard; backfill 100/s per job and 1K/s overall; redrive 100/s | Moves due `DELAYED` runs (retries, deferrals, backfill, redrive) to `QUEUED` with outbox rows |
| Outbox relay | kairos-watcher | Every 100 ms per shard group | Batch 2K rows; Kafka `acks=all`, idempotent producer; optional dispatch cap per second | Publishes due outbox rows, deletes them after the acknowledgement |
| Lease sweeper | kairos-watcher | Every 5s | Margin 30s after `lease_until`; max 3 infrastructure re-queues | Recovers runs whose executor died, using the Redis lock and sent marker |
| Stuck-queued sweeper | kairos-watcher | Every 1 min | `QUEUED` older than 10 min | Safety net: re-dispatches and raises an alert |
| Redis failover monitor | kairos-watcher | Every 2s | Quarantine 310s | Detects a Redis primary change; missing sent markers then count as unknown outcomes |
| Auto-suspend evaluator | kairos-watcher (one instance) | Every 15s | ≥ 50 dead letters in 5 min, or ≥ 80% failures over ≥ 200 attempts; 10-minute cool-down | Suspends failing endpoints, audits and alerts |
| Review-timeout sweeper | kairos-watcher (one instance) | Every 60s | Review timeout 24h | Fails dependents of `BLOCKED` workflow runs past their deadline |
| Purger | kairos-watcher (one instance) | Every 1h | Retention 7 days; idempotency records 24h; expired sessions | Deletes resolved DLQ entries, deleted definitions, expired records |
| Dispatch consumers | kairos-executor | Continuous | Topics `kairos.run.high/medium/low`, poll 250 / 150 / 100; in-flight high/low water 4K / 3K per pod; lock TTL = timeout + 10s; send guard 2s; sent marker TTL 48h; connect timeout 3s; response cap 256 KB; breaker 50% over 100 calls, open 30s; drain 60s | Lock, claim, render, call, classify, publish result |
| Cancel listener | kairos-executor | Continuous | Topic `kairos.run.cancel`, one consumer group per pod | Aborts the matching in-flight call |
| Result settler | kairos-state | Continuous | Topic `kairos.run.result`, ≤ 1K records per batch, one transaction per cell; retries 5, base 30s, cap 10 min, max age 24h; auto-pause after 3 dead runs | Writes history, schedules retries, dead-letters, advances workflows, writes follow-up outbox rows |
| Stats aggregator | kairos-state | Every 10s | Per minute and node | Flushes `endpoint_health_minute` and `team_stats_minute` |
| Partition manager | kairos-maintenance | Daily (CronJob) | Create partitions 3 days ahead; drop after 7 days (audit 90 days) | Manages `run_history`, `task_outputs` and `audit_log` partitions |
| Operator commands | kairos-maintenance | On demand | `team set-quota`, `user disable`, `user reset-password` | Audited operator actions |

### 4.2 Kafka topics

| Topic | Key | Partitions | Retention | Producer → consumer |
|---|---|---|---|---|
| `kairos.run.high` / `.medium` / `.low` | target id | 24 / 48 / 24 | 24h | outbox relay → executors |
| `kairos.run.result` | target id | 48 | 24h | executors → kairos-state |
| `kairos.run.cancel` | run id | 6 | 1h | outbox relay → every executor |
| `kairos.run.completed` | job id | 12 | 3 days | outbox relay → subscribers (opt-in per team) |
| `kairos.run.dead` | job id | 6 | 14 days | outbox relay → alerting |

Replication factor 3, `min.insync.replicas = 2`, Protobuf payloads. A dispatch message carries the run id, fence, attempt, pinned endpoint version and a snapshot of the request values, never secrets.

### 4.3 Redis keys

| Key | Value and TTL | Used by |
|---|---|---|
| `kairos:lock:run:{runId}` | Owner token (fence, node, random); TTL = timeout + 10s | Executor (take, release), lease sweeper (check) |
| `kairos:sent:{runId}:{attempt}:{infraAttempt}` | `1`; TTL 48h | Executor (write), lease sweeper (check) |
| `kairos:rl:{bucket}` | Token bucket | API rate limits: login per IP and account, signup per IP, member add and team create per user, team quotas |
