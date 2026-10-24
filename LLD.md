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

**team_keysets** (PK `team_id, purpose`): `team_id` uuid, `purpose` text (`SECRETS`, `OUTPUTS`, `SIGNING`), `encrypted_keyset` bytea (sealed by Cloud KMS), `kek_uri` text, `updated_at` timestamptz, `previous_key_valid_until` timestamptz (nullable; SIGNING only, persisted overlap deadline).

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
| spec | jsonb | Validated endpoint configuration (section 3.5), ≤ 64 KB |
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
| name | text | `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`, cannot be changed |
| version | int | Current version |
| status | text | `ACTIVE`, `PAUSED`, `AUTO_PAUSED`, `COMPLETED`, `FAILED`, `CANCELLED`, `DELETED` |
| endpoint_id | uuid | Endpoint in the control database |
| spec | jsonb | Validated job definition (section 3.6), ≤ 128 KB |
| created_by | text | `user:{id}` or `key:{keyId}` |
| created_at, updated_at | timestamptz | |

**job_versions** (PK `job_id, version`, immutable): `job_id` uuid, `version` int, `spec` jsonb, `created_at` timestamptz.

**workflows**: same shape as `jobs` without `endpoint_id`; `workflow_id` uuid PK; `name` follows the job name rule; `status` `ACTIVE`, `PAUSED`, `CANCELLED`, `DELETED`; `spec` jsonb ≤ 512 KB (tasks, edges and the computed plan).

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
| dst_policy | smallint | 1 gap: run at the first valid instant after it, overlap: run at the first occurrence (default); 2 gap: shift forward, overlap: run at both; 3 gap: skip, overlap: first; 4 gap: skip, overlap: both |
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
| task_key | text | Workflow task key (`^[A-Za-z][A-Za-z0-9_-]{0,63}$`), empty for jobs |
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
| error_class | smallint | 1 `TIMEOUT`, 2 `DNS_FAILED`, 3 `CONNECT_FAILED`, 4 `TLS_FAILED`, 5 `HTTP_4XX`, 6 `HTTP_429`, 7 `HTTP_5XX`, 8 `UNKNOWN_OUTCOME`, 9 `RESPONSE_TOO_LARGE`, 10 `OUTPUT_MISSING`, 11 `RENDER_FAILED`, 12 `TARGET_NOT_ALLOWED`, 13 `MAX_AGE_EXCEEDED`, 14 `EXECUTOR_LOST` |
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
| fence | int | The run's fence when it was dead-lettered; a redrive continues from it |
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
| Request headers | `Content-Type: application/json`; `Idempotency-Key: <1–64 chars>` (optional, on creates marked *idempotent*); `If-Match: "<version>"` (required on versioned resource updates; secret rotation adds a version without this header); `traceparent` (optional) |
| Response headers | `Content-Type: application/json` (or `application/problem+json` for errors); `ETag: "<version>"` on single resources; `Location` on `201`; `X-Request-Id`; `Retry-After` on `429` and `503` |
| Errors | RFC 9457 problem details: `{"type", "title", "status", "detail", "instance", "traceId", "errors": [{"field", "code"}]}`. Unknown fields in a request are rejected with `400`. |
| Common statuses | `400` validation, `401` not authenticated, `403` `no-team` (user without a team), `404` not found or another team's resource, `409` conflict, `412` version mismatch, `429` rate limit or quota |
| Pagination | `?limit=1..200` (default 50) and `?cursor=<opaque>`; responses are `{"items": [...], "nextCursor": "..." }` |
| Team | Always taken from the credential; never sent in the path or body |

**Errors common to every authenticated API** (not repeated per API below):

| Status | Code | Message |
|---|---|---|
| `401` | `unauthenticated` | A valid access token or API key is required. |
| `403` | `no-team` | Create a team or ask a member to add this user. |
| `429` | `rate-limited` / `quota-exceeded` | Too many requests. Retry after the time in `Retry-After`. |
| `500` | `internal` | An unexpected error occurred. Quote the traceId when reporting it. |
| `503` | `unavailable` | The service is temporarily unavailable. Retry after the time in `Retry-After`. |

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json

{"type": "/errors/unauthenticated", "title": "Authentication required", "status": 401, "detail": "A valid access token or API key is required.", "instance": "/v1/jobs", "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"}
```

Every error response also carries `instance` (the request path) and `traceId`; they are left out of the examples below for brevity. Error messages never echo submitted values, stack traces or database errors.

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

### 3.3 Accounts

<details>
<summary><b>1. Sign up</b> &nbsp; <code>POST /v1/auth/signup</code></summary>

**Request**

```http
POST /v1/auth/signup HTTP/1.1
Host: kairos.internal.example.com
Content-Type: application/json

{
  "email": "asha@example.com",
  "password": "<password, 12-128 characters>"
}
```

**Success: `201 Created`**

```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: /v1/me

{
  "userId": "0192e7a9-1f3c-7d8e-9a0b-1c2d3e4f5a6b",
  "email": "asha@example.com",
  "teamId": null,
  "createdAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | Password must be 12 to 128 characters and not a common password. |
| `409` | `email-taken` | An account with this email already exists. |
| `429` | `rate-limited` | Too many signups from this address. Retry later. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "Password must be 12 to 128 characters and not a common password.", "errors": [{"field": "password", "code": "too-short"}]}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/email-taken", "title": "Email already registered", "status": 409, "detail": "An account with this email already exists."}

HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json

{"type": "/errors/rate-limited", "title": "Too many requests", "status": 429, "detail": "Too many signups from this address. Retry later."}
```

**Background effect:** None. The new user has no team until one is created or a member adds them.

</details>

<details>
<summary><b>2. Log in</b> &nbsp; <code>POST /v1/auth/login</code></summary>

**Request**

```http
POST /v1/auth/login HTTP/1.1
Host: kairos.internal.example.com
Content-Type: application/json

{
  "email": "asha@example.com",
  "password": "<password, 12-128 characters>"
}
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store

{
  "accessToken": "eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTAifQ.eyJzdWIiOiIwMTkyZTdhOSJ9.sig",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshToken": "rt_q3V9mZ2xK7pL4sN8wB1cD6fH0jR5tY2u",
  "refreshExpiresIn": 2592000,
  "teamId": "0192e7b0-4d2a-7c11-8f3e-6a9b1c2d3e4f"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |
| `401` | `invalid-credentials` | Invalid email or password. |
| `429` | `rate-limited` | Too many login attempts. Retry later. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "email", "code": "required"}]}

HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json

{"type": "/errors/invalid-credentials", "title": "Login failed", "status": 401, "detail": "Invalid email or password."}

HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json

{"type": "/errors/rate-limited", "title": "Too many requests", "status": 429, "detail": "Too many login attempts. Retry later."}
```

**Background effect:** None. Repeated failures lock the account for a growing period; a locked account gets the same 401.

</details>

<details>
<summary><b>3. Refresh tokens</b> &nbsp; <code>POST /v1/auth/refresh</code></summary>

**Request**

```http
POST /v1/auth/refresh HTTP/1.1
Host: kairos.internal.example.com
Content-Type: application/json

{
  "refreshToken": "rt_q3V9mZ2xK7pL4sN8wB1cD6fH0jR5tY2u"
}
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store

{
  "accessToken": "eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTAifQ.eyJzdWIiOiIwMTkyZTdhOSJ9.sig",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshToken": "rt_N4bV7cX1zL8kJ2hG5fD9sA3pO6iU0yT1",
  "refreshExpiresIn": 2592000,
  "teamId": "0192e7b0-4d2a-7c11-8f3e-6a9b1c2d3e4f"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `401` | `invalid-refresh-token` | The refresh token is invalid, expired or already used. |

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json

{"type": "/errors/invalid-refresh-token", "title": "Refresh failed", "status": 401, "detail": "The refresh token is invalid, expired or already used."}
```

**Background effect:** The presented token is marked used. Presenting an already-used token revokes the whole session family.

</details>

<details>
<summary><b>4. Log out</b> &nbsp; <code>POST /v1/auth/logout</code></summary>

**Request**

```http
POST /v1/auth/logout HTTP/1.1
Host: kairos.internal.example.com
Content-Type: application/json

{
  "refreshToken": "rt_N4bV7cX1zL8kJ2hG5fD9sA3pO6iU0yT1"
}
```

**Success: `204 No Content`**

```http
HTTP/1.1 204 No Content
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "refreshToken", "code": "required"}]}
```

**Background effect:** The session is revoked. Unknown or already revoked tokens also return 204.

</details>

<details>
<summary><b>5. Get my profile</b> &nbsp; <code>GET /v1/me</code></summary>

**Request**

```http
GET /v1/me HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTAifQ.eyJzdWIiOiIwMTkyZTdhOSJ9.sig
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "userId": "0192e7a9-1f3c-7d8e-9a0b-1c2d3e4f5a6b",
  "email": "asha@example.com",
  "teamId": "0192e7b0-4d2a-7c11-8f3e-6a9b1c2d3e4f",
  "teamName": "payments",
  "createdAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `401` | `unauthenticated` | A valid access token is required. |

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json

{"type": "/errors/unauthenticated", "title": "Authentication required", "status": 401, "detail": "A valid access token is required."}
```

**Background effect:** None.

</details>

<details>
<summary><b>6. Change password</b> &nbsp; <code>POST /v1/me/password</code></summary>

**Request**

```http
POST /v1/me/password HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTAifQ.eyJzdWIiOiIwMTkyZTdhOSJ9.sig
Content-Type: application/json

{
  "currentPassword": "<password, 12-128 characters>",
  "newPassword": "<new password, 12-128 characters>"
}
```

**Success: `204 No Content`**

```http
HTTP/1.1 204 No Content
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | Password must be 12 to 128 characters and not a common password. |
| `400` | `invalid-current-password` | The current password is incorrect. |
| `429` | `rate-limited` | Too many attempts. Retry later. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "Password must be 12 to 128 characters and not a common password.", "errors": [{"field": "newPassword", "code": "too-short"}]}

HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/invalid-current-password", "title": "Password not changed", "status": 400, "detail": "The current password is incorrect."}

HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json

{"type": "/errors/rate-limited", "title": "Too many requests", "status": 429, "detail": "Too many attempts. Retry later."}
```

**Background effect:** All other sessions of the user are revoked.

</details>

<details>
<summary><b>7. Get token signing keys</b> &nbsp; <code>GET /.well-known/jwks.json</code></summary>

**Request**

```http
GET /.well-known/jwks.json HTTP/1.1
Host: kairos.internal.example.com
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: public, max-age=300

{
  "keys": [
    {
      "kty": "EC",
      "crv": "P-256",
      "kid": "k202610",
      "use": "sig",
      "alg": "ES256",
      "x": "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU",
      "y": "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"
    }
  ]
}
```

**Background effect:** None.

</details>

### 3.4 Team

<details>
<summary><b>8. Create a team</b> &nbsp; <code>POST /v1/teams</code></summary>

**Request**

```http
POST /v1/teams HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTAifQ.eyJzdWIiOiIwMTkyZTdhOSJ9.sig
Content-Type: application/json

{
  "name": "payments"
}
```

**Success: `201 Created`**

```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: /v1/team

{
  "teamId": "0192e7b0-4d2a-7c11-8f3e-6a9b1c2d3e4f",
  "name": "payments",
  "quotas": {
    "maxJobs": 10000,
    "maxExecPerSec": 2000,
    "maxInflight": 2000,
    "maxWorkflowTasks": 200,
    "backfillPerSec": 100,
    "redrivePerSec": 100
  },
  "signingKey": "ksig_v1_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "createdAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | Team name must match ^[a-z][a-z0-9-]{2,62}$. |
| `409` | `already-in-team` | The user already belongs to a team. |
| `409` | `team-name-taken` | A team with this name already exists. |
| `429` | `rate-limited` | Too many teams created. Retry later. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "Team name must match ^[a-z][a-z0-9-]{2,62}$.", "errors": [{"field": "name", "code": "pattern"}]}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/already-in-team", "title": "Already in a team", "status": 409, "detail": "The user already belongs to a team."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/team-name-taken", "title": "Team name taken", "status": 409, "detail": "A team with this name already exists."}

HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json

{"type": "/errors/rate-limited", "title": "Too many requests", "status": 429, "detail": "Too many teams created. Retry later."}
```

**Background effect:** Creates the team's encryption and signing keysets. `signingKey` (used by targets to verify `X-Kairos-Signature`) is shown only once. Call refresh to get a token that carries the team.

</details>

<details>
<summary><b>9. Get my team</b> &nbsp; <code>GET /v1/team</code></summary>

**Request**

```http
GET /v1/team HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "teamId": "0192e7b0-4d2a-7c11-8f3e-6a9b1c2d3e4f",
  "name": "payments",
  "quotas": {
    "maxJobs": 10000,
    "maxExecPerSec": 2000,
    "maxInflight": 2000,
    "maxWorkflowTasks": 200,
    "backfillPerSec": 100,
    "redrivePerSec": 100
  },
  "usage": {
    "jobs": 1240,
    "execPerSec": 310,
    "inflight": 42
  },
  "createdAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `403` | `no-team` | Create a team or ask a member to add this user. |

```http
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json

{"type": "/errors/no-team", "title": "No team", "status": 403, "detail": "Create a team or ask a member to add this user."}
```

**Background effect:** None.

</details>

<details>
<summary><b>10. List members</b> &nbsp; <code>GET /v1/team/members</code></summary>

**Request**

```http
# query param: limit = 1..200 (default 50)
# query param: cursor = opaque value from nextCursor
GET /v1/team/members HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "items": [
    {
      "userId": "0192e7a9-1f3c-7d8e-9a0b-1c2d3e4f5a6b",
      "email": "asha@example.com",
      "addedAt": "2026-10-14T09:30:00Z"
    }
  ],
  "nextCursor": null
}
```

**Background effect:** None.

</details>

<details>
<summary><b>11. Add a member</b> &nbsp; <code>POST /v1/team/members</code></summary>

**Request**

```http
POST /v1/team/members HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTAifQ.eyJzdWIiOiIwMTkyZTdhOSJ9.sig
Content-Type: application/json

{
  "email": "ravi@example.com"
}
```

**Success: `201 Created`**

```http
HTTP/1.1 201 Created
Content-Type: application/json

{
  "userId": "0192e7c3-6b5a-7e9d-8c1f-2a3b4c5d6e7f",
  "email": "ravi@example.com",
  "addedAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |
| `403` | `forbidden-for-api-key` | This operation requires a user access token. |
| `404` | `user-not-found` | No account exists for this email. |
| `409` | `user-in-another-team` | The user already belongs to another team. |
| `429` | `rate-limited` | Too many members added. Retry later. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "email", "code": "format"}]}

HTTP/1.1 403 Forbidden
Content-Type: application/problem+json

{"type": "/errors/forbidden-for-api-key", "title": "Not allowed for API keys", "status": 403, "detail": "This operation requires a user access token."}

HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/user-not-found", "title": "User not found", "status": 404, "detail": "No account exists for this email."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/user-in-another-team", "title": "User in another team", "status": 409, "detail": "The user already belongs to another team."}

HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json

{"type": "/errors/rate-limited", "title": "Too many requests", "status": 429, "detail": "Too many members added. Retry later."}
```

**Background effect:** None. The added user gets the team on their next token refresh.

</details>

<details>
<summary><b>12. Remove a member or leave</b> &nbsp; <code>DELETE /v1/team/members/{userId}</code></summary>

**Request**

```http
# path param:  userId = member's user id (own id to leave)
DELETE /v1/team/members/0192e7c3-6b5a-7e9d-8c1f-2a3b4c5d6e7f HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTAifQ.eyJzdWIiOiIwMTkyZTdhOSJ9.sig
```

**Success: `204 No Content`**

```http
HTTP/1.1 204 No Content
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `403` | `forbidden-for-api-key` | This operation requires a user access token. |
| `404` | `not-found` | The member does not exist. |
| `409` | `last-member` | A team must keep at least one member. |

```http
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json

{"type": "/errors/forbidden-for-api-key", "title": "Not allowed for API keys", "status": 403, "detail": "This operation requires a user access token."}

HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The member does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/last-member", "title": "Last member", "status": 409, "detail": "A team must keep at least one member."}
```

**Background effect:** The user's team is cleared and all their sessions are revoked; existing access tokens stop working within 30s.

</details>

<details>
<summary><b>13. Create an API key</b> &nbsp; <code>POST /v1/api-keys</code></summary>

**Request**

```http
POST /v1/api-keys HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTAifQ.eyJzdWIiOiIwMTkyZTdhOSJ9.sig
Content-Type: application/json

{
  "name": "ci-pipeline",
  "expiresAt": "2027-04-14T00:00:00Z"
}
```

**Success: `201 Created`**

```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: /v1/api-keys/7Hq2Lm9Xc4Vb8Nz1

{
  "keyId": "7Hq2Lm9Xc4Vb8Nz1",
  "name": "ci-pipeline",
  "key": "kairos_7Hq2Lm9Xc4Vb8Nz1_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "expiresAt": "2027-04-14T00:00:00Z",
  "createdAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |
| `403` | `forbidden-for-api-key` | This operation requires a user access token. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "name", "code": "length"}]}

HTTP/1.1 403 Forbidden
Content-Type: application/problem+json

{"type": "/errors/forbidden-for-api-key", "title": "Not allowed for API keys", "status": 403, "detail": "This operation requires a user access token."}
```

**Background effect:** None. The full `key` is returned only in this response; only its hash is stored.

</details>

<details>
<summary><b>14. List API keys</b> &nbsp; <code>GET /v1/api-keys</code></summary>

**Request**

```http
# query param: limit = 1..200 (default 50)
# query param: cursor = opaque value from nextCursor
GET /v1/api-keys HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTAifQ.eyJzdWIiOiIwMTkyZTdhOSJ9.sig
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "items": [
    {
      "keyId": "7Hq2Lm9Xc4Vb8Nz1",
      "name": "ci-pipeline",
      "createdBy": "0192e7a9-1f3c-7d8e-9a0b-1c2d3e4f5a6b",
      "expiresAt": "2027-04-14T00:00:00Z",
      "lastUsedAt": "2026-10-14T09:30:00.084Z",
      "revokedAt": null
    }
  ],
  "nextCursor": null
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `403` | `forbidden-for-api-key` | This operation requires a user access token. |

```http
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json

{"type": "/errors/forbidden-for-api-key", "title": "Not allowed for API keys", "status": 403, "detail": "This operation requires a user access token."}
```

**Background effect:** None.

</details>

<details>
<summary><b>15. Revoke an API key</b> &nbsp; <code>DELETE /v1/api-keys/{keyId}</code></summary>

**Request**

```http
# path param:  keyId = public key id
DELETE /v1/api-keys/7Hq2Lm9Xc4Vb8Nz1 HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTAifQ.eyJzdWIiOiIwMTkyZTdhOSJ9.sig
```

**Success: `204 No Content`**

```http
HTTP/1.1 204 No Content
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `403` | `forbidden-for-api-key` | This operation requires a user access token. |
| `404` | `not-found` | The API key does not exist. |

```http
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json

{"type": "/errors/forbidden-for-api-key", "title": "Not allowed for API keys", "status": 403, "detail": "This operation requires a user access token."}

HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The API key does not exist."}
```

**Background effect:** Cached key lookups expire within 60s, after which the key is rejected everywhere.

</details>

Credential resource names (`secrets` and `ca-bundles`) are 1–128 characters matching `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`. Secret fields are strings, at most 8192 characters each, without control characters; bearer tokens use printable ASCII without spaces, and BASIC usernames cannot contain a colon. The serialized plaintext is capped at 16320 bytes so the sealed value fits the 16 KB database limit. Secret metadata lists the current version and the original creation time. Secret rotation creates a new version and does not require `If-Match`. CA bundles contain 1–10 currently valid X.509 CA certificates (certificate blocks only), at most 64 KB; a key-usage extension, when present, must allow certificate signing. Both lists support the common cursor pagination contract. Deletion checks both current endpoint specifications and retained immutable endpoint versions, so configurations pinned by runs keep their credentials and trust anchors.

<details>
<summary><b>16. Create a secret</b> &nbsp; <code>POST /v1/secrets</code></summary>

**Request**

```http
POST /v1/secrets HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json

{
  "name": "orders-api-token",
  "kind": "BEARER",
  "value": {
    "token": "<target bearer token>"
  }
}
```

**Success: `201 Created`**

```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: /v1/secrets/orders-api-token

{
  "name": "orders-api-token",
  "kind": "BEARER",
  "version": 1,
  "createdAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | `value` must match the kind: BASIC {username, password}, BEARER {token}, API_KEY {key}, OAUTH2_CLIENT {clientId, clientSecret}. |
| `409` | `secret-exists` | A secret with this name already exists. Use PUT to add a version. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "`value` must match the kind: BASIC {username, password}, BEARER {token}, API_KEY {key}, OAUTH2_CLIENT {clientId, clientSecret}.", "errors": [{"field": "value.token", "code": "required"}]}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/secret-exists", "title": "Secret exists", "status": 409, "detail": "A secret with this name already exists. Use PUT to add a version."}
```

**Background effect:** The value is encrypted with the team's key before it is stored and is never returned.

</details>

<details>
<summary><b>17. List secrets</b> &nbsp; <code>GET /v1/secrets</code></summary>

**Request**

```http
# query param: limit = 1..200 (default 50)
# query param: cursor = opaque value from nextCursor
GET /v1/secrets HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "items": [
    {
      "name": "orders-api-token",
      "kind": "BEARER",
      "version": 2,
      "createdAt": "2026-10-14T09:30:00Z"
    }
  ],
  "nextCursor": null
}
```

**Background effect:** None.

</details>

<details>
<summary><b>18. Rotate a secret value</b> &nbsp; <code>PUT /v1/secrets/{name}</code></summary>

**Request**

```http
# path param:  name = secret name
PUT /v1/secrets/orders-api-token HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json

{
  "value": {
    "token": "<new target bearer token>"
  }
}
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "name": "orders-api-token",
  "kind": "BEARER",
  "version": 2,
  "createdAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |
| `404` | `not-found` | The secret does not exist. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "value.token", "code": "required"}]}

HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The secret does not exist."}
```

**Background effect:** New calls use the new version within 60s (executor secret cache).

</details>

<details>
<summary><b>19. Delete a secret</b> &nbsp; <code>DELETE /v1/secrets/{name}</code></summary>

**Request**

```http
# path param:  name = secret name
DELETE /v1/secrets/orders-api-token HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `204 No Content`**

```http
HTTP/1.1 204 No Content
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The secret does not exist. |
| `409` | `secret-in-use` | The secret is used by endpoint orders-create. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The secret does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/secret-in-use", "title": "Secret in use", "status": 409, "detail": "The secret is used by endpoint orders-create."}
```

**Background effect:** None.

</details>

<details>
<summary><b>20. Add a CA bundle</b> &nbsp; <code>POST /v1/ca-bundles</code></summary>

**Request**

```http
POST /v1/ca-bundles HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json

{
  "name": "corp-internal-ca",
  "pem": "-----BEGIN CERTIFICATE-----\nMIIB...\n-----END CERTIFICATE-----"
}
```

**Success: `201 Created`**

```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: /v1/ca-bundles/corp-internal-ca

{
  "name": "corp-internal-ca",
  "createdAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | The PEM must contain one or more valid CA certificates. |
| `409` | `ca-bundle-exists` | A CA bundle with this name already exists. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "The PEM must contain one or more valid CA certificates.", "errors": [{"field": "pem", "code": "invalid-certificate"}]}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/ca-bundle-exists", "title": "CA bundle exists", "status": 409, "detail": "A CA bundle with this name already exists."}
```

**Background effect:** None.

</details>

<details>
<summary><b>21. List CA bundles</b> &nbsp; <code>GET /v1/ca-bundles</code></summary>

**Request**

```http
GET /v1/ca-bundles HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "items": [
    {
      "name": "corp-internal-ca",
      "createdAt": "2026-10-14T09:30:00Z"
    }
  ],
  "nextCursor": null
}
```

**Background effect:** None.

</details>

<details>
<summary><b>22. Delete a CA bundle</b> &nbsp; <code>DELETE /v1/ca-bundles/{name}</code></summary>

**Request**

```http
# path param:  name = CA bundle name
DELETE /v1/ca-bundles/corp-internal-ca HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `204 No Content`**

```http
HTTP/1.1 204 No Content
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The CA bundle does not exist. |
| `409` | `ca-bundle-in-use` | The CA bundle is used by endpoint orders-create. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The CA bundle does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/ca-bundle-in-use", "title": "CA bundle in use", "status": 409, "detail": "The CA bundle is used by endpoint orders-create."}
```

**Background effect:** None.

</details>

<details>
<summary><b>23. Rotate the request-signing key</b> &nbsp; <code>POST /v1/team/signing-key:rotate</code></summary>

**Request**

```http
POST /v1/team/signing-key:rotate HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTAifQ.eyJzdWIiOiIwMTkyZTdhOSJ9.sig
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "signingKey": "ksig_v2_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "keyVersion": 2,
  "previousKeyValidUntil": "2026-10-15T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `403` | `forbidden-for-api-key` | This operation requires a user access token. |

```http
HTTP/1.1 403 Forbidden
Content-Type: application/problem+json

{"type": "/errors/forbidden-for-api-key", "title": "Not allowed for API keys", "status": 403, "detail": "This operation requires a user access token."}
```

**Background effect:** Requests are signed with the new key at once; targets should accept both keys until `previousKeyValidUntil` (default overlap 24h). The overlap deadline is persisted. Each rotation retains the immediately previous key; the expiry job removes non-primary keys after the deadline. Targets enforce the returned deadline for their own verification keys.

</details>

<details>
<summary><b>24. Read the audit log</b> &nbsp; <code>GET /v1/audit-log</code></summary>

**Request**

```http
# query param: from, to = time range (default last 24h, at most 90 days back)
# query param: action = filter, for example JOB_DELETED
# query param: limit = 1..200 (default 50)
# query param: cursor = opaque value from nextCursor
GET /v1/audit-log?from=2026-10-14T00:00:00Z&action=JOB_DELETED HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "items": [
    {
      "at": "2026-10-14T09:30:00Z",
      "actor": "user:0192e7a9-1f3c-7d8e-9a0b-1c2d3e4f5a6b",
      "action": "JOB_DELETED",
      "resourceType": "job",
      "resourceId": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
      "details": {
        "name": "nightly-order-sync"
      }
    }
  ],
  "nextCursor": "eyJrIjoiMjAyNi0xMC0xNFQwOTozMDowMFoifQ"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "from", "code": "out-of-range"}]}
```

**Background effect:** None.

</details>

### 3.5 Endpoints

Endpoint body rules: `method` is `GET`, `POST`, `PUT`, `PATCH`, `DELETE` or `HEAD`; `urlTemplate` must be HTTPS, pass the platform target policy and use `{param}` only in the path; `auth.type` is `NONE`, `BASIC`, `BEARER`, `API_KEY` or `OAUTH2_CLIENT_CREDENTIALS`, with credentials only through `secretRef`; `API_KEY` also takes `location` (`HEADER` or `QUERY`) and `name` (the header or query parameter that carries the key); `OAUTH2_CLIENT_CREDENTIALS` also takes `tokenUrl` (HTTPS, checked by the same target policy) and an optional `scope`, sends the client id and secret as HTTP Basic, and caches the token until 60s before `expires_in` (a token without `expires_in` is used once and not cached); `defaultHeaders` may not set headers the client manages (`Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Keep-Alive`, `Proxy-Connection`, `Proxy-Authorization`, `Upgrade`, `TE`, `Trailer`, `Expect`), and header names and values may not contain CR or LF; the rendered request body is at most 1 MiB; redirects are never followed (a 3xx is returned as the response); `timeoutMs` 1000–300000 (default 30000); `successStatus` has 1–10 entries, each a status class (`2xx`) or a status (100–599); `statusOverrides` maps up to 50 three-digit statuses to `SUCCESS`, `RETRY` or `FAIL` and is checked before `successStatus`; a status that is neither overridden nor listed is classified as `HTTP_429` (429) or `HTTP_5XX` (5xx), both retried, `HTTP_4XX` retried for 408 and 425, and `HTTP_4XX` not retried for every other status, including an unlisted 1xx, 2xx or 3xx; `retryPolicy` takes `maxRetries` 0–20, `baseDelaySec` 1–3600, `maxDelaySec` from `baseDelaySec` to 86400 and `maxAgeSec` 60–604800 (7 days); retry `strategy` is `EXPONENTIAL_JITTER`, `FULL_JITTER` or `FIXED`; `unknownOutcomePolicy` is `RETRY_WITH_IDEMPOTENCY_KEY` (default) or `REVIEW`.

<details>
<summary><b>25. Register an endpoint</b> &nbsp; <code>POST /v1/endpoints</code></summary>

**Request**

```http
POST /v1/endpoints HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json

{
  "name": "orders-create",
  "method": "POST",
  "urlTemplate": "https://orders.payments.svc.cluster.local:8443/v1/customers/{customerId}/orders",
  "defaultHeaders": {
    "X-Source": "kairos"
  },
  "auth": {
    "type": "BEARER",
    "secretRef": "orders-api-token"
  },
  "caBundle": null,
  "timeoutMs": 10000,
  "successStatus": [
    "2xx"
  ],
  "statusOverrides": {
    "409": "SUCCESS"
  },
  "retryPolicy": {
    "maxRetries": 5,
    "strategy": "EXPONENTIAL_JITTER",
    "baseDelaySec": 30,
    "maxDelaySec": 600,
    "maxAgeSec": 86400
  },
  "limits": {
    "maxConcurrency": 200,
    "ratePerSec": 1000
  },
  "outputs": {
    "orderId": {
      "from": "BODY",
      "path": "$.data.id",
      "required": true,
      "sensitive": false
    }
  },
  "unknownOutcomePolicy": "RETRY_WITH_IDEMPOTENCY_KEY",
  "suspendPolicy": {
    "enabled": true
  }
}
```

**Success: `201 Created`**

```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: /v1/endpoints/0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e
ETag: "1"

{
  "id": "0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e",
  "name": "orders-create",
  "method": "POST",
  "urlTemplate": "https://orders.payments.svc.cluster.local:8443/v1/customers/{customerId}/orders",
  "defaultHeaders": {
    "X-Source": "kairos"
  },
  "auth": {
    "type": "BEARER",
    "secretRef": "orders-api-token"
  },
  "caBundle": null,
  "timeoutMs": 10000,
  "successStatus": [
    "2xx"
  ],
  "statusOverrides": {
    "409": "SUCCESS"
  },
  "retryPolicy": {
    "maxRetries": 5,
    "strategy": "EXPONENTIAL_JITTER",
    "baseDelaySec": 30,
    "maxDelaySec": 600,
    "maxAgeSec": 86400
  },
  "limits": {
    "maxConcurrency": 200,
    "ratePerSec": 1000
  },
  "outputs": {
    "orderId": {
      "from": "BODY",
      "path": "$.data.id",
      "required": true,
      "sensitive": false
    }
  },
  "unknownOutcomePolicy": "RETRY_WITH_IDEMPOTENCY_KEY",
  "suspendPolicy": {
    "enabled": true
  },
  "status": "ACTIVE",
  "statusReason": null,
  "version": 1,
  "createdAt": "2026-10-14T09:30:00Z",
  "updatedAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |
| `400` | `target-not-allowed` | The host is not an internal address allowed by the platform target policy. |
| `400` | `secret-not-found` | auth.secretRef refers to a secret that does not exist. |
| `409` | `endpoint-name-taken` | An endpoint with this name already exists. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "urlTemplate", "code": "https-required"}]}

HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/target-not-allowed", "title": "Target not allowed", "status": 400, "detail": "The host is not an internal address allowed by the platform target policy."}

HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/secret-not-found", "title": "Unknown secret", "status": 400, "detail": "auth.secretRef refers to a secret that does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/endpoint-name-taken", "title": "Endpoint name taken", "status": 409, "detail": "An endpoint with this name already exists."}
```

**Background effect:** None.

</details>

<details>
<summary><b>26. List endpoints</b> &nbsp; <code>GET /v1/endpoints</code></summary>

**Request**

```http
# query param: status = ACTIVE | SUSPENDED | DISABLED
# query param: limit = 1..200 (default 50)
# query param: cursor = opaque value from nextCursor
GET /v1/endpoints?status=ACTIVE HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "items": [
    {
      "id": "0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e",
      "name": "orders-create",
      "method": "POST",
      "status": "ACTIVE",
      "version": 3,
      "updatedAt": "2026-10-14T09:30:00Z"
    }
  ],
  "nextCursor": "eyJrIjoiMjAyNi0xMC0xNFQwOTozMDowMFoifQ"
}
```

**Background effect:** None.

</details>

<details>
<summary><b>27. Get an endpoint</b> &nbsp; <code>GET /v1/endpoints/{id}</code></summary>

**Request**

```http
# path param:  id = endpoint id
GET /v1/endpoints/0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json
ETag: "3"

{
  "id": "0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e",
  "name": "orders-create",
  "method": "POST",
  "urlTemplate": "https://orders.payments.svc.cluster.local:8443/v1/customers/{customerId}/orders",
  "defaultHeaders": {
    "X-Source": "kairos"
  },
  "auth": {
    "type": "BEARER",
    "secretRef": "orders-api-token"
  },
  "caBundle": null,
  "timeoutMs": 10000,
  "successStatus": [
    "2xx"
  ],
  "statusOverrides": {
    "409": "SUCCESS"
  },
  "retryPolicy": {
    "maxRetries": 5,
    "strategy": "EXPONENTIAL_JITTER",
    "baseDelaySec": 30,
    "maxDelaySec": 600,
    "maxAgeSec": 86400
  },
  "limits": {
    "maxConcurrency": 200,
    "ratePerSec": 1000
  },
  "outputs": {
    "orderId": {
      "from": "BODY",
      "path": "$.data.id",
      "required": true,
      "sensitive": false
    }
  },
  "unknownOutcomePolicy": "RETRY_WITH_IDEMPOTENCY_KEY",
  "suspendPolicy": {
    "enabled": true
  },
  "status": "ACTIVE",
  "statusReason": null,
  "version": 3,
  "createdAt": "2026-10-14T09:30:00Z",
  "updatedAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The endpoint does not exist. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The endpoint does not exist."}
```

**Background effect:** None.

</details>

<details>
<summary><b>28. Update an endpoint</b> &nbsp; <code>PUT /v1/endpoints/{id}</code></summary>

**Request**

```http
# path param:  id = endpoint id
PUT /v1/endpoints/0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json
If-Match: "3"

{
  "name": "orders-create",
  "method": "POST",
  "urlTemplate": "https://orders.payments.svc.cluster.local:8443/v1/customers/{customerId}/orders",
  "defaultHeaders": {
    "X-Source": "kairos"
  },
  "auth": {
    "type": "BEARER",
    "secretRef": "orders-api-token"
  },
  "caBundle": null,
  "timeoutMs": 15000,
  "successStatus": [
    "2xx"
  ],
  "statusOverrides": {
    "409": "SUCCESS"
  },
  "retryPolicy": {
    "maxRetries": 5,
    "strategy": "EXPONENTIAL_JITTER",
    "baseDelaySec": 30,
    "maxDelaySec": 600,
    "maxAgeSec": 86400
  },
  "limits": {
    "maxConcurrency": 200,
    "ratePerSec": 1000
  },
  "outputs": {
    "orderId": {
      "from": "BODY",
      "path": "$.data.id",
      "required": true,
      "sensitive": false
    }
  },
  "unknownOutcomePolicy": "RETRY_WITH_IDEMPOTENCY_KEY",
  "suspendPolicy": {
    "enabled": true
  }
}
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json
ETag: "4"

{
  "id": "0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e",
  "name": "orders-create",
  "method": "POST",
  "urlTemplate": "https://orders.payments.svc.cluster.local:8443/v1/customers/{customerId}/orders",
  "defaultHeaders": {
    "X-Source": "kairos"
  },
  "auth": {
    "type": "BEARER",
    "secretRef": "orders-api-token"
  },
  "caBundle": null,
  "timeoutMs": 15000,
  "successStatus": [
    "2xx"
  ],
  "statusOverrides": {
    "409": "SUCCESS"
  },
  "retryPolicy": {
    "maxRetries": 5,
    "strategy": "EXPONENTIAL_JITTER",
    "baseDelaySec": 30,
    "maxDelaySec": 600,
    "maxAgeSec": 86400
  },
  "limits": {
    "maxConcurrency": 200,
    "ratePerSec": 1000
  },
  "outputs": {
    "orderId": {
      "from": "BODY",
      "path": "$.data.id",
      "required": true,
      "sensitive": false
    }
  },
  "unknownOutcomePolicy": "RETRY_WITH_IDEMPOTENCY_KEY",
  "suspendPolicy": {
    "enabled": true
  },
  "status": "ACTIVE",
  "statusReason": null,
  "version": 4,
  "createdAt": "2026-10-14T09:30:00Z",
  "updatedAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |
| `404` | `not-found` | The endpoint does not exist. |
| `428` | `if-match-required` | Send the current version in the If-Match header. |
| `412` | `version-mismatch` | The resource was changed by another request. Fetch it and retry with the new ETag. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "timeoutMs", "code": "range"}]}

HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The endpoint does not exist."}

HTTP/1.1 428 Precondition Required
Content-Type: application/problem+json

{"type": "/errors/if-match-required", "title": "Precondition required", "status": 428, "detail": "Send the current version in the If-Match header."}

HTTP/1.1 412 Precondition Failed
Content-Type: application/problem+json

{"type": "/errors/version-mismatch", "title": "Version mismatch", "status": 412, "detail": "The resource was changed by another request. Fetch it and retry with the new ETag."}
```

**Background effect:** A new immutable version is created. Jobs use it within 5s; running workflow runs keep their pinned version.

</details>

<details>
<summary><b>29. Delete an endpoint</b> &nbsp; <code>DELETE /v1/endpoints/{id}</code></summary>

**Request**

```http
# path param:  id = endpoint id
DELETE /v1/endpoints/0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `204 No Content`**

```http
HTTP/1.1 204 No Content
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The endpoint does not exist. |
| `409` | `endpoint-in-use` | 12 jobs still reference this endpoint. Delete them or disable the endpoint. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The endpoint does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/endpoint-in-use", "title": "Endpoint in use", "status": 409, "detail": "12 jobs still reference this endpoint. Delete them or disable the endpoint."}
```

**Background effect:** None.

</details>

<details>
<summary><b>30. Test an endpoint</b> &nbsp; <code>POST /v1/endpoints/{id}:test</code></summary>

**Request**

```http
# path param:  id = endpoint id
POST /v1/endpoints/0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e:test HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json

{
  "pathParams": {
    "customerId": "42"
  },
  "query": {},
  "headers": {},
  "body": {
    "mode": "dry-run"
  }
}
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "httpStatus": 201,
  "latencyMs": 92,
  "outcome": "SUCCESS",
  "outputs": {
    "orderId": "ord_9x2k"
  }
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The endpoint does not exist. |
| `400` | `render-failed` | Path parameter customerId is missing. |
| `429` | `rate-limited` | Endpoint tests are limited to 10 per minute. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The endpoint does not exist."}

HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/render-failed", "title": "Request could not be built", "status": 400, "detail": "Path parameter customerId is missing."}

HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json

{"type": "/errors/rate-limited", "title": "Too many requests", "status": 429, "detail": "Endpoint tests are limited to 10 per minute."}
```

**Background effect:** One synchronous call through an executor. Nothing is stored; sensitive outputs are redacted.

The API calls `POST /internal/v1/endpoint-tests` on the executor over the private backend network. Its request contains `endpointId`, `pathParams`, `query`, `headers` and optional `body`. The API signs a service token with the platform JWT signing keyset, using issuer `kairos`, audience `kairos-executor-internal`, a 30-second lifetime, and the caller's team claim. The executor verifies all claims and rejects tokens with a longer lifetime. The Helm deployment must restrict this route to `kairos-api`; local Compose keeps the executor on the internal backend network without a published port. The API enforces the public per-team limit of 10 requests per minute; the executor also applies a per-team, per-process limit as defense in depth. The executor loads the active endpoint's current immutable version, uses the normal renderer, authentication, TLS and HTTP client, and returns a redacted status, latency, outcome, nonsensitive outputs and error. The route does not use run locks, circuit breakers, dispatch attempts or result publication. Sensitive outputs are represented as `<redacted>`.

</details>

<details>
<summary><b>31. Suspend an endpoint</b> &nbsp; <code>POST /v1/endpoints/{id}/suspend</code></summary>

**Request**

```http
# path param:  id = endpoint id
POST /v1/endpoints/0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e/suspend HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json

{
  "reason": "Orders service maintenance"
}
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "id": "0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e",
  "name": "orders-create",
  "method": "POST",
  "status": "SUSPENDED",
  "version": 3,
  "updatedAt": "2026-10-14T09:30:00Z",
  "statusReason": "Orders service maintenance"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The endpoint does not exist. |
| `409` | `invalid-state` | The endpoint is already suspended. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The endpoint does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/invalid-state", "title": "Invalid state", "status": 409, "detail": "The endpoint is already suspended."}
```

**Background effect:** The watcher holds the schedules of jobs using it, and the state processor defers their retries.

</details>

<details>
<summary><b>32. Resume an endpoint</b> &nbsp; <code>POST /v1/endpoints/{id}/resume</code></summary>

**Request**

```http
# path param:  id = endpoint id
POST /v1/endpoints/0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e/resume HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "id": "0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e",
  "name": "orders-create",
  "method": "POST",
  "status": "ACTIVE",
  "version": 3,
  "updatedAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The endpoint does not exist. |
| `409` | `invalid-state` | The endpoint is not suspended. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The endpoint does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/invalid-state", "title": "Invalid state", "status": 409, "detail": "The endpoint is not suspended."}
```

**Background effect:** Held schedules fire again; fires missed while suspended follow each job's misfire policy.

</details>

<details>
<summary><b>33. Get endpoint health</b> &nbsp; <code>GET /v1/endpoints/{id}/health</code></summary>

**Request**

```http
# path param:  id = endpoint id
# query param: window = 5m..24h (default 1h)
GET /v1/endpoints/0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e/health?window=1h HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "window": "1h",
  "attempts": 182400,
  "successes": 181950,
  "failureRatio": 0.0025,
  "p95LatencyMs": 140,
  "deadLettered": 3,
  "breaker": "CLOSED"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The endpoint does not exist. |
| `400` | `validation` | One or more fields are invalid. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The endpoint does not exist."}

HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "window", "code": "range"}]}
```

**Background effect:** None.

</details>

### 3.6 Jobs

Job body rules: `name` is 1–128 characters matching `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`; `schedule.type` is `IMMEDIATE`, `ONE_TIME` (with `at`), `CRON` (with `expression` and `timezone`) or `INTERVAL` (with `everySec`); `priority` 1–10 (8–10 high, 5–7 medium, 1–4 low tier); `misfirePolicy` is `RUN_LATEST` (default), `RUN_ALL` or `SKIP`; `retryPolicy` overrides the endpoint's as a whole, with the same ranges; `autoPauseAfter` is 0–100, and 0 disables auto-pause.

<details>
<summary><b>34. Create a job</b> &nbsp; <code>POST /v1/jobs</code></summary>

**Request**

```http
POST /v1/jobs HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json
Idempotency-Key: 9b1d6f2e-5c3a-4e8f-a1b2-c3d4e5f6a7b8

{
  "name": "nightly-order-sync",
  "schedule": {
    "type": "CRON",
    "expression": "0 2 * * *",
    "timezone": "Asia/Kolkata",
    "spreadSec": 0
  },
  "endpoint": "orders-create",
  "request": {
    "pathParams": {
      "customerId": "42"
    },
    "query": {},
    "headers": {},
    "body": {
      "mode": "sync"
    }
  },
  "priority": 7,
  "retryPolicy": null,
  "misfirePolicy": "RUN_LATEST",
  "maxCatchup": 100,
  "maxLatenessSec": null,
  "autoPauseAfter": 3
}
```

**Success: `201 Created`**

```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: /v1/jobs/0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9
ETag: "1"

{
  "id": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
  "name": "nightly-order-sync",
  "schedule": {
    "type": "CRON",
    "expression": "0 2 * * *",
    "timezone": "Asia/Kolkata",
    "spreadSec": 0
  },
  "endpoint": "orders-create",
  "request": {
    "pathParams": {
      "customerId": "42"
    },
    "query": {},
    "headers": {},
    "body": {
      "mode": "sync"
    }
  },
  "priority": 7,
  "retryPolicy": null,
  "misfirePolicy": "RUN_LATEST",
  "maxCatchup": 100,
  "maxLatenessSec": null,
  "autoPauseAfter": 3,
  "status": "ACTIVE",
  "version": 1,
  "nextFireAt": "2026-10-14T20:30:00Z",
  "lastRun": null,
  "createdAt": "2026-10-14T09:30:00Z",
  "updatedAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |
| `400` | `endpoint-not-found` | Endpoint orders-create does not exist in this team. |
| `409` | `job-name-taken` | A job with this name already exists in the team. |
| `409` | `idempotency-key-reused` | This Idempotency-Key was already used with a different request body. |
| `429` | `quota-exceeded` | The team has reached its limit of 10K jobs. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "schedule.expression", "code": "invalid-cron"}]}

HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/endpoint-not-found", "title": "Unknown endpoint", "status": 400, "detail": "Endpoint orders-create does not exist in this team."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/job-name-taken", "title": "Job name taken", "status": 409, "detail": "A job with this name already exists in the team."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/idempotency-key-reused", "title": "Idempotency key reused", "status": 409, "detail": "This Idempotency-Key was already used with a different request body."}

HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json

{"type": "/errors/quota-exceeded", "title": "Quota exceeded", "status": 429, "detail": "The team has reached its limit of 10K jobs."}
```

**Background effect:** A schedule row is created and the fire poller fires it when due. An `IMMEDIATE` job inserts its run and outbox row in the same transaction, and the relay publishes it within about 100 ms.

</details>

<details>
<summary><b>35. Create jobs in bulk</b> &nbsp; <code>POST /v1/jobs:batch</code></summary>

**Request**

```http
POST /v1/jobs:batch HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json
Idempotency-Key: 9b1d6f2e-5c3a-4e8f-a1b2-c3d4e5f6a7b8

{
  "jobs": [
    {
      "name": "nightly-order-sync",
      "schedule": {
        "type": "CRON",
        "expression": "0 2 * * *",
        "timezone": "Asia/Kolkata",
        "spreadSec": 0
      },
      "endpoint": "orders-create",
      "request": {
        "pathParams": {
          "customerId": "42"
        },
        "query": {},
        "headers": {},
        "body": {
          "mode": "sync"
        }
      },
      "priority": 7,
      "retryPolicy": null,
      "misfirePolicy": "RUN_LATEST",
      "maxCatchup": 100,
      "maxLatenessSec": null,
      "autoPauseAfter": 3
    },
    {
      "name": "hourly-stock-sync",
      "schedule": {
        "type": "INTERVAL",
        "everySec": 3600
      },
      "endpoint": "orders-create",
      "request": {
        "pathParams": {
          "customerId": "42"
        },
        "query": {},
        "headers": {},
        "body": {
          "mode": "sync"
        }
      },
      "priority": 7,
      "retryPolicy": null,
      "misfirePolicy": "RUN_LATEST",
      "maxCatchup": 100,
      "maxLatenessSec": null,
      "autoPauseAfter": 3
    }
  ]
}
```

**Success: `207 Multi-Status`**

```http
HTTP/1.1 207 Multi-Status
Content-Type: application/json

{
  "results": [
    {
      "index": 0,
      "status": 201,
      "id": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9"
    },
    {
      "index": 1,
      "status": 409,
      "error": {
        "type": "/errors/job-name-taken",
        "detail": "A job with this name already exists in the team."
      }
    }
  ]
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | A batch holds at most 1K jobs. |
| `409` | `idempotency-key-reused` | This Idempotency-Key was already used with a different request body. |
| `429` | `quota-exceeded` | The batch would exceed the team's limit of 10K jobs. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "A batch holds at most 1K jobs.", "errors": [{"field": "jobs", "code": "too-many"}]}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/idempotency-key-reused", "title": "Idempotency key reused", "status": 409, "detail": "This Idempotency-Key was already used with a different request body."}

HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json

{"type": "/errors/quota-exceeded", "title": "Quota exceeded", "status": 429, "detail": "The batch would exceed the team's limit of 10K jobs."}
```

**Background effect:** Same as create, per job. Jobs are grouped by cell and committed per cell, so one item's failure does not roll back the others.

</details>

<details>
<summary><b>36. List jobs</b> &nbsp; <code>GET /v1/jobs</code></summary>

**Request**

```http
# query param: status = ACTIVE | PAUSED | AUTO_PAUSED | COMPLETED | FAILED | CANCELLED
# query param: endpoint = endpoint name
# query param: name = name prefix
# query param: limit = 1..200 (default 50)
# query param: cursor = opaque value from nextCursor
GET /v1/jobs?status=ACTIVE&endpoint=orders-create HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "items": [
    {
      "id": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
      "name": "nightly-order-sync",
      "status": "ACTIVE",
      "endpoint": "orders-create",
      "priority": 7,
      "nextFireAt": "2026-10-14T20:30:00Z",
      "lastRun": {
        "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
        "status": "SUCCEEDED",
        "finishedAt": "2026-10-14T09:30:00.084Z"
      }
    }
  ],
  "nextCursor": "eyJrIjoiMjAyNi0xMC0xNFQwOTozMDowMFoifQ"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "status", "code": "enum"}]}
```

**Background effect:** None. Reads every cell's replica in parallel; deleted jobs are excluded.

</details>

<details>
<summary><b>37. Get a job</b> &nbsp; <code>GET /v1/jobs/{id}</code></summary>

**Request**

```http
# path param:  id = job id
GET /v1/jobs/0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9 HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json
ETag: "3"

{
  "id": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
  "name": "nightly-order-sync",
  "schedule": {
    "type": "CRON",
    "expression": "0 2 * * *",
    "timezone": "Asia/Kolkata",
    "spreadSec": 0
  },
  "endpoint": "orders-create",
  "request": {
    "pathParams": {
      "customerId": "42"
    },
    "query": {},
    "headers": {},
    "body": {
      "mode": "sync"
    }
  },
  "priority": 7,
  "retryPolicy": null,
  "misfirePolicy": "RUN_LATEST",
  "maxCatchup": 100,
  "maxLatenessSec": null,
  "autoPauseAfter": 3,
  "status": "ACTIVE",
  "version": 3,
  "nextFireAt": "2026-10-14T20:30:00Z",
  "lastRun": {
    "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
    "status": "SUCCEEDED",
    "finishedAt": "2026-10-14T09:30:00.084Z"
  },
  "createdAt": "2026-10-14T09:30:00Z",
  "updatedAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The job does not exist. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The job does not exist."}
```

**Background effect:** None.

</details>

<details>
<summary><b>38. Update a job</b> &nbsp; <code>PUT /v1/jobs/{id}</code></summary>

**Request**

```http
# path param:  id = job id
PUT /v1/jobs/0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9 HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json
If-Match: "3"

{
  "name": "nightly-order-sync",
  "schedule": {
    "type": "CRON",
    "expression": "30 2 * * *",
    "timezone": "Asia/Kolkata",
    "spreadSec": 0
  },
  "endpoint": "orders-create",
  "request": {
    "pathParams": {
      "customerId": "42"
    },
    "query": {},
    "headers": {},
    "body": {
      "mode": "sync"
    }
  },
  "priority": 7,
  "retryPolicy": null,
  "misfirePolicy": "RUN_LATEST",
  "maxCatchup": 100,
  "maxLatenessSec": null,
  "autoPauseAfter": 3
}
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json
ETag: "4"

{
  "id": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
  "name": "nightly-order-sync",
  "schedule": {
    "type": "CRON",
    "expression": "30 2 * * *",
    "timezone": "Asia/Kolkata",
    "spreadSec": 0
  },
  "endpoint": "orders-create",
  "request": {
    "pathParams": {
      "customerId": "42"
    },
    "query": {},
    "headers": {},
    "body": {
      "mode": "sync"
    }
  },
  "priority": 7,
  "retryPolicy": null,
  "misfirePolicy": "RUN_LATEST",
  "maxCatchup": 100,
  "maxLatenessSec": null,
  "autoPauseAfter": 3,
  "status": "ACTIVE",
  "version": 4,
  "nextFireAt": "2026-10-14T21:00:00Z",
  "lastRun": {
    "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
    "status": "SUCCEEDED",
    "finishedAt": "2026-10-14T09:30:00.084Z"
  },
  "createdAt": "2026-10-14T09:30:00Z",
  "updatedAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |
| `404` | `not-found` | The job does not exist. |
| `400` | `name-immutable` | A job's name cannot be changed. |
| `428` | `if-match-required` | Send the current version in the If-Match header. |
| `412` | `version-mismatch` | The resource was changed by another request. Fetch it and retry with the new ETag. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "schedule.expression", "code": "invalid-cron"}]}

HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The job does not exist."}

HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/name-immutable", "title": "Name cannot change", "status": 400, "detail": "A job's name cannot be changed."}

HTTP/1.1 428 Precondition Required
Content-Type: application/problem+json

{"type": "/errors/if-match-required", "title": "Precondition required", "status": 428, "detail": "Send the current version in the If-Match header."}

HTTP/1.1 412 Precondition Failed
Content-Type: application/problem+json

{"type": "/errors/version-mismatch", "title": "Version mismatch", "status": 412, "detail": "The resource was changed by another request. Fetch it and retry with the new ETag."}
```

**Background effect:** `next_fire_at` is recomputed. Runs already queued keep the version they were created with.

</details>

<details>
<summary><b>39. Delete a job</b> &nbsp; <code>DELETE /v1/jobs/{id}</code></summary>

**Request**

```http
# path param:  id = job id
DELETE /v1/jobs/0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9 HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `204 No Content`**

```http
HTTP/1.1 204 No Content
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The job does not exist. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The job does not exist."}
```

**Background effect:** In one transaction: the schedule is removed, waiting/queued/delayed runs are cancelled, running runs get a cancel message on `kairos.run.cancel`, and the job becomes `DELETED`. History stays readable until the 7-day purge; the name can be reused at once. Repeating the call returns 404.

</details>

<details>
<summary><b>40. Pause a job</b> &nbsp; <code>POST /v1/jobs/{id}/pause</code></summary>

**Request**

```http
# path param:  id = job id
POST /v1/jobs/0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9/pause HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "id": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
  "name": "nightly-order-sync",
  "status": "PAUSED",
  "endpoint": "orders-create",
  "priority": 7,
  "nextFireAt": "2026-10-14T20:30:00Z",
  "lastRun": {
    "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
    "status": "SUCCEEDED",
    "finishedAt": "2026-10-14T09:30:00.084Z"
  }
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The job does not exist. |
| `409` | `invalid-state` | Only an ACTIVE job can be paused. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The job does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/invalid-state", "title": "Invalid state", "status": 409, "detail": "Only an ACTIVE job can be paused."}
```

**Background effect:** The watcher stops firing it. Runs already queued still execute.

</details>

<details>
<summary><b>41. Resume a job</b> &nbsp; <code>POST /v1/jobs/{id}/resume</code></summary>

**Request**

```http
# path param:  id = job id
POST /v1/jobs/0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9/resume HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "id": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
  "name": "nightly-order-sync",
  "status": "ACTIVE",
  "endpoint": "orders-create",
  "priority": 7,
  "nextFireAt": "2026-10-14T20:30:00Z",
  "lastRun": {
    "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
    "status": "SUCCEEDED",
    "finishedAt": "2026-10-14T09:30:00.084Z"
  }
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The job does not exist. |
| `409` | `invalid-state` | Only a PAUSED or AUTO_PAUSED job can be resumed. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The job does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/invalid-state", "title": "Invalid state", "status": 409, "detail": "Only a PAUSED or AUTO_PAUSED job can be resumed."}
```

**Background effect:** The next fire time is computed; fires missed while paused follow the job's misfire policy.

</details>

<details>
<summary><b>42. Cancel a job</b> &nbsp; <code>POST /v1/jobs/{id}/cancel</code></summary>

**Request**

```http
# path param:  id = job id
POST /v1/jobs/0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9/cancel HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "id": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
  "name": "nightly-order-sync",
  "status": "CANCELLED",
  "endpoint": "orders-create",
  "priority": 7,
  "nextFireAt": null,
  "lastRun": {
    "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
    "status": "SUCCEEDED",
    "finishedAt": "2026-10-14T09:30:00.084Z"
  }
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The job does not exist. |
| `409` | `invalid-state` | The job is already cancelled. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The job does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/invalid-state", "title": "Invalid state", "status": 409, "detail": "The job is already cancelled."}
```

**Background effect:** Same as delete, but the job stays visible with status `CANCELLED`.

</details>

<details>
<summary><b>43. Run a job now</b> &nbsp; <code>POST /v1/jobs/{id}/runnow</code></summary>

**Request**

```http
# path param:  id = job id
POST /v1/jobs/0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9/runnow HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json
Idempotency-Key: 9b1d6f2e-5c3a-4e8f-a1b2-c3d4e5f6a7b8

{
  "request": {
    "body": {
      "mode": "full"
    }
  }
}
```

**Success: `202 Accepted`**

```http
HTTP/1.1 202 Accepted
Content-Type: application/json
Location: /v1/runs/0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9

{
  "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
  "status": "QUEUED"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The job does not exist. |
| `409` | `invalid-state` | A cancelled job cannot be run. |
| `409` | `idempotency-key-reused` | This Idempotency-Key was already used with a different request body. |
| `429` | `quota-exceeded` | The team is over its executions-per-second quota. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The job does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/invalid-state", "title": "Invalid state", "status": 409, "detail": "A cancelled job cannot be run."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/idempotency-key-reused", "title": "Idempotency key reused", "status": 409, "detail": "This Idempotency-Key was already used with a different request body."}

HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json

{"type": "/errors/quota-exceeded", "title": "Quota exceeded", "status": 429, "detail": "The team is over its executions-per-second quota."}
```

**Background effect:** A run and its outbox row are inserted in one transaction; the relay publishes it within about 100 ms. `request` optionally overrides values for this run only.

</details>

<details>
<summary><b>44. Backfill a job</b> &nbsp; <code>POST /v1/jobs/{id}/backfill</code></summary>

**Request**

```http
# path param:  id = job id
POST /v1/jobs/0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9/backfill HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json

{
  "from": "2026-10-10T00:00:00Z",
  "to": "2026-10-13T00:00:00Z",
  "dryRun": false
}
```

**Success: `202 Accepted`**

```http
HTTP/1.1 202 Accepted
Content-Type: application/json

{
  "occurrences": 3,
  "firstAt": "2026-10-10T20:30:00Z",
  "lastAt": "2026-10-12T20:30:00Z",
  "dryRun": false
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The job does not exist. |
| `400` | `range-too-large` | A backfill can create at most 10K occurrences. |
| `409` | `not-recurring` | Backfill applies only to CRON and INTERVAL jobs. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The job does not exist."}

HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/range-too-large", "title": "Range too large", "status": 400, "detail": "A backfill can create at most 10K occurrences."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/not-recurring", "title": "Not a recurring job", "status": 409, "detail": "Backfill applies only to CRON and INTERVAL jobs."}
```

**Background effect:** Occurrences are inserted as `DELAYED` runs and released by the delayed poller at 100/s per job. Occurrences that already ran are skipped.

</details>

<details>
<summary><b>45. List a job's runs</b> &nbsp; <code>GET /v1/jobs/{id}/runs</code></summary>

**Request**

```http
# path param:  id = job id
# query param: status = WAITING | QUEUED | RUNNING | DELAYED | SUCCEEDED | DEAD_LETTERED | CANCELLED | SKIPPED
# query param: from, to = scheduled time range (within the last 7 days)
# query param: limit = 1..200 (default 50)
# query param: cursor = opaque value from nextCursor
GET /v1/jobs/0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9/runs?status=DEAD_LETTERED HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "items": [
    {
      "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
      "jobId": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
      "status": "SUCCEEDED",
      "attempt": 2,
      "scheduledAt": "2026-10-14T09:30:00Z",
      "finishedAt": "2026-10-14T09:30:31Z",
      "httpStatus": 201,
      "latencyMs": 84
    }
  ],
  "nextCursor": "eyJrIjoiMjAyNi0xMC0xNFQwOTozMDowMFoifQ"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The job does not exist. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The job does not exist."}
```

**Background effect:** None.

</details>

### 3.7 Workflows and workflow runs

Workflow body rules: `name` follows the job name rule; task keys match `^[A-Za-z][A-Za-z0-9_-]{0,63}$`; acyclic, at most 200 tasks and 10 levels; placeholders (`{{ tasks.<task>.outputs.<name> }}`, `{{ run.id }}`) may reference only declared outputs of ancestor tasks and never the URL host; `onTaskFailure` is `BLOCK_FOR_REVIEW` (default) or `FAIL_FAST`.

Template values support only `{{ run.id }}` and `{{ tasks.<task>.outputs.<name> }}`. The producer must be an ancestor and the named output must be declared by its resolved endpoint configuration. A single placeholder used as a JSON value preserves its type; mixed text accepts scalar values only. JSON property names, URL hosts and schemes cannot contain placeholders. Sensitive outputs may render into headers or body values, never path or query values. Path segments and query values use UTF-8 percent encoding exactly once; path traversal, pre-encoded path separators/traversal sequences, and header control characters are rejected. Templates are limited to 16384 characters per field, rendered scalar fields to 64 KiB, and JSON trees to 32 nested levels and 16384 nodes; rendered bodies remain capped at 1 MiB. Total request-template bytes across a workflow cannot exceed 512 KiB.

Tasks use at most 256 declared outputs. BODY selectors support `$`, dot properties and non-negative array indexes such as `$.data.items[0].id`; recursive searches, wildcards and expressions are unsupported. HEADER selectors match names without case sensitivity and require a single unambiguous value. STATUS produces a JSON number. Missing required values fail extraction; missing optional values are omitted, while an explicit JSON null remains a value. The 24 KiB output limit includes serialized names, JSON structure and sensitive values. Multiple independent roots are valid; a cyclic component is invalid even when other roots are reachable.


<details>
<summary><b>46. Create a workflow</b> &nbsp; <code>POST /v1/workflows</code></summary>

**Request**

```http
POST /v1/workflows HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json
Idempotency-Key: 9b1d6f2e-5c3a-4e8f-a1b2-c3d4e5f6a7b8

{
  "name": "order-fulfilment",
  "schedule": {
    "type": "CRON",
    "expression": "*/15 * * * *",
    "timezone": "Asia/Kolkata"
  },
  "priority": 5,
  "onTaskFailure": "BLOCK_FOR_REVIEW",
  "reviewTimeoutSec": 86400,
  "tasks": {
    "login": {
      "endpoint": "auth-service-token"
    },
    "createOrder": {
      "endpoint": "orders-create",
      "dependsOn": [
        "login"
      ],
      "request": {
        "headers": {
          "Authorization": "Bearer {{ tasks.login.outputs.accessToken }}"
        },
        "body": {
          "runRef": "{{ run.id }}"
        }
      }
    },
    "notify": {
      "endpoint": "notify-send",
      "dependsOn": [
        "createOrder"
      ],
      "request": {
        "body": {
          "orderId": "{{ tasks.createOrder.outputs.orderId }}"
        }
      }
    }
  }
}
```

**Success: `201 Created`**

```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: /v1/workflows/0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a
ETag: "1"

{
  "id": "0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a",
  "name": "order-fulfilment",
  "schedule": {
    "type": "CRON",
    "expression": "*/15 * * * *",
    "timezone": "Asia/Kolkata"
  },
  "priority": 5,
  "onTaskFailure": "BLOCK_FOR_REVIEW",
  "reviewTimeoutSec": 86400,
  "tasks": {
    "login": {
      "endpoint": "auth-service-token"
    },
    "createOrder": {
      "endpoint": "orders-create",
      "dependsOn": [
        "login"
      ],
      "request": {
        "headers": {
          "Authorization": "Bearer {{ tasks.login.outputs.accessToken }}"
        },
        "body": {
          "runRef": "{{ run.id }}"
        }
      }
    },
    "notify": {
      "endpoint": "notify-send",
      "dependsOn": [
        "createOrder"
      ],
      "request": {
        "body": {
          "orderId": "{{ tasks.createOrder.outputs.orderId }}"
        }
      }
    }
  },
  "status": "ACTIVE",
  "version": 1,
  "nextFireAt": "2026-10-14T09:45:00Z",
  "createdAt": "2026-10-14T09:30:00Z",
  "updatedAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `cycle-detected` | Tasks login -> createOrder -> login form a cycle. |
| `400` | `invalid-placeholder` | notify references tasks.payment.outputs.id, which is not an upstream output. |
| `400` | `too-many-tasks` | A workflow has at most 200 tasks and 10 levels. |
| `409` | `workflow-name-taken` | A workflow with this name already exists in the team. |
| `409` | `idempotency-key-reused` | This Idempotency-Key was already used with a different request body. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/cycle-detected", "title": "Invalid workflow", "status": 400, "detail": "Tasks login -> createOrder -> login form a cycle."}

HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/invalid-placeholder", "title": "Invalid workflow", "status": 400, "detail": "notify references tasks.payment.outputs.id, which is not an upstream output."}

HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/too-many-tasks", "title": "Invalid workflow", "status": 400, "detail": "A workflow has at most 200 tasks and 10 levels."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/workflow-name-taken", "title": "Workflow name taken", "status": 409, "detail": "A workflow with this name already exists in the team."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/idempotency-key-reused", "title": "Idempotency key reused", "status": 409, "detail": "This Idempotency-Key was already used with a different request body."}
```

**Background effect:** A schedule row is created. On each fire the watcher creates one workflow run and one run per task: root tasks `QUEUED` with outbox rows, the others `WAITING` with their dependency counts.

</details>

<details>
<summary><b>47. List workflows</b> &nbsp; <code>GET /v1/workflows</code></summary>

**Request**

```http
# query param: status = ACTIVE | PAUSED | CANCELLED
# query param: limit = 1..200 (default 50)
# query param: cursor = opaque value from nextCursor
GET /v1/workflows HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "items": [
    {
      "id": "0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a",
      "name": "order-fulfilment",
      "status": "ACTIVE",
      "taskCount": 3,
      "nextFireAt": "2026-10-14T09:45:00Z"
    }
  ],
  "nextCursor": "eyJrIjoiMjAyNi0xMC0xNFQwOTozMDowMFoifQ"
}
```

**Background effect:** None.

</details>

<details>
<summary><b>48. Get a workflow</b> &nbsp; <code>GET /v1/workflows/{id}</code></summary>

**Request**

```http
# path param:  id = workflow id
GET /v1/workflows/0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json
ETag: "2"

{
  "id": "0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a",
  "name": "order-fulfilment",
  "schedule": {
    "type": "CRON",
    "expression": "*/15 * * * *",
    "timezone": "Asia/Kolkata"
  },
  "priority": 5,
  "onTaskFailure": "BLOCK_FOR_REVIEW",
  "reviewTimeoutSec": 86400,
  "tasks": {
    "login": {
      "endpoint": "auth-service-token"
    },
    "createOrder": {
      "endpoint": "orders-create",
      "dependsOn": [
        "login"
      ],
      "request": {
        "headers": {
          "Authorization": "Bearer {{ tasks.login.outputs.accessToken }}"
        },
        "body": {
          "runRef": "{{ run.id }}"
        }
      }
    },
    "notify": {
      "endpoint": "notify-send",
      "dependsOn": [
        "createOrder"
      ],
      "request": {
        "body": {
          "orderId": "{{ tasks.createOrder.outputs.orderId }}"
        }
      }
    }
  },
  "status": "ACTIVE",
  "version": 2,
  "nextFireAt": "2026-10-14T09:45:00Z",
  "createdAt": "2026-10-14T09:30:00Z",
  "updatedAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The workflow does not exist. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The workflow does not exist."}
```

**Background effect:** None.

</details>

<details>
<summary><b>49. Update a workflow</b> &nbsp; <code>PUT /v1/workflows/{id}</code></summary>

**Request**

```http
# path param:  id = workflow id
PUT /v1/workflows/0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json
If-Match: "3"

{
  "name": "order-fulfilment",
  "schedule": {
    "type": "CRON",
    "expression": "*/15 * * * *",
    "timezone": "Asia/Kolkata"
  },
  "priority": 8,
  "onTaskFailure": "BLOCK_FOR_REVIEW",
  "reviewTimeoutSec": 86400,
  "tasks": {
    "login": {
      "endpoint": "auth-service-token"
    },
    "createOrder": {
      "endpoint": "orders-create",
      "dependsOn": [
        "login"
      ],
      "request": {
        "headers": {
          "Authorization": "Bearer {{ tasks.login.outputs.accessToken }}"
        },
        "body": {
          "runRef": "{{ run.id }}"
        }
      }
    },
    "notify": {
      "endpoint": "notify-send",
      "dependsOn": [
        "createOrder"
      ],
      "request": {
        "body": {
          "orderId": "{{ tasks.createOrder.outputs.orderId }}"
        }
      }
    }
  }
}
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json
ETag: "3"

{
  "id": "0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a",
  "name": "order-fulfilment",
  "schedule": {
    "type": "CRON",
    "expression": "*/15 * * * *",
    "timezone": "Asia/Kolkata"
  },
  "priority": 8,
  "onTaskFailure": "BLOCK_FOR_REVIEW",
  "reviewTimeoutSec": 86400,
  "tasks": {
    "login": {
      "endpoint": "auth-service-token"
    },
    "createOrder": {
      "endpoint": "orders-create",
      "dependsOn": [
        "login"
      ],
      "request": {
        "headers": {
          "Authorization": "Bearer {{ tasks.login.outputs.accessToken }}"
        },
        "body": {
          "runRef": "{{ run.id }}"
        }
      }
    },
    "notify": {
      "endpoint": "notify-send",
      "dependsOn": [
        "createOrder"
      ],
      "request": {
        "body": {
          "orderId": "{{ tasks.createOrder.outputs.orderId }}"
        }
      }
    }
  },
  "status": "ACTIVE",
  "version": 3,
  "nextFireAt": "2026-10-14T09:45:00Z",
  "createdAt": "2026-10-14T09:30:00Z",
  "updatedAt": "2026-10-14T09:30:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `cycle-detected` | Tasks login -> createOrder -> login form a cycle. |
| `404` | `not-found` | The workflow does not exist. |
| `428` | `if-match-required` | Send the current version in the If-Match header. |
| `412` | `version-mismatch` | The resource was changed by another request. Fetch it and retry with the new ETag. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/cycle-detected", "title": "Invalid workflow", "status": 400, "detail": "Tasks login -> createOrder -> login form a cycle."}

HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The workflow does not exist."}

HTTP/1.1 428 Precondition Required
Content-Type: application/problem+json

{"type": "/errors/if-match-required", "title": "Precondition required", "status": 428, "detail": "Send the current version in the If-Match header."}

HTTP/1.1 412 Precondition Failed
Content-Type: application/problem+json

{"type": "/errors/version-mismatch", "title": "Version mismatch", "status": 412, "detail": "The resource was changed by another request. Fetch it and retry with the new ETag."}
```

**Background effect:** New fires use the new version; workflow runs already started keep their pinned version.

</details>

<details>
<summary><b>50. Delete a workflow</b> &nbsp; <code>DELETE /v1/workflows/{id}</code></summary>

**Request**

```http
# path param:  id = workflow id
DELETE /v1/workflows/0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `204 No Content`**

```http
HTTP/1.1 204 No Content
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The workflow does not exist. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The workflow does not exist."}
```

**Background effect:** Schedule removed, open workflow runs cancelled (running tasks get a cancel message), status `DELETED`, purged after 7 days.

</details>

<details>
<summary><b>51. Pause a workflow</b> &nbsp; <code>POST /v1/workflows/{id}/pause</code></summary>

**Request**

```http
# path param:  id = workflow id
POST /v1/workflows/0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a/pause HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "id": "0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a",
  "name": "order-fulfilment",
  "status": "PAUSED",
  "taskCount": 3,
  "nextFireAt": "2026-10-14T09:45:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The workflow does not exist. |
| `409` | `invalid-state` | Only an ACTIVE workflow can be paused. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The workflow does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/invalid-state", "title": "Invalid state", "status": 409, "detail": "Only an ACTIVE workflow can be paused."}
```

**Background effect:** The watcher stops firing it; open workflow runs continue.

</details>

<details>
<summary><b>52. Resume a workflow</b> &nbsp; <code>POST /v1/workflows/{id}/resume</code></summary>

**Request**

```http
# path param:  id = workflow id
POST /v1/workflows/0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a/resume HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "id": "0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a",
  "name": "order-fulfilment",
  "status": "ACTIVE",
  "taskCount": 3,
  "nextFireAt": "2026-10-14T09:45:00Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The workflow does not exist. |
| `409` | `invalid-state` | Only a PAUSED workflow can be resumed. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The workflow does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/invalid-state", "title": "Invalid state", "status": 409, "detail": "Only a PAUSED workflow can be resumed."}
```

**Background effect:** Missed fires follow the misfire policy.

</details>

<details>
<summary><b>53. Cancel a workflow</b> &nbsp; <code>POST /v1/workflows/{id}/cancel</code></summary>

**Request**

```http
# path param:  id = workflow id
POST /v1/workflows/0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a/cancel HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "id": "0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a",
  "name": "order-fulfilment",
  "status": "CANCELLED",
  "taskCount": 3,
  "nextFireAt": null
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The workflow does not exist. |
| `409` | `invalid-state` | The workflow is already cancelled. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The workflow does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/invalid-state", "title": "Invalid state", "status": 409, "detail": "The workflow is already cancelled."}
```

**Background effect:** Like delete, but the workflow stays visible.

</details>

<details>
<summary><b>54. Run a workflow now</b> &nbsp; <code>POST /v1/workflows/{id}/runnow</code></summary>

**Request**

```http
# path param:  id = workflow id
POST /v1/workflows/0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a/runnow HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Idempotency-Key: 9b1d6f2e-5c3a-4e8f-a1b2-c3d4e5f6a7b8
```

**Success: `202 Accepted`**

```http
HTTP/1.1 202 Accepted
Content-Type: application/json
Location: /v1/workflow-runs/0192f3b6-8e9f-7a0b-8c1d-2e3f4a5b6c7a

{
  "workflowRunId": "0192f3b6-8e9f-7a0b-8c1d-2e3f4a5b6c7a",
  "status": "RUNNING"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The workflow does not exist. |
| `409` | `invalid-state` | A cancelled workflow cannot be run. |
| `409` | `idempotency-key-reused` | This Idempotency-Key was already used with a different request body. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The workflow does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/invalid-state", "title": "Invalid state", "status": 409, "detail": "A cancelled workflow cannot be run."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/idempotency-key-reused", "title": "Idempotency key reused", "status": 409, "detail": "This Idempotency-Key was already used with a different request body."}
```

**Background effect:** The workflow run, its task runs and the root tasks' outbox rows are written in one transaction.

</details>

<details>
<summary><b>55. List a workflow's runs</b> &nbsp; <code>GET /v1/workflows/{id}/runs</code></summary>

**Request**

```http
# path param:  id = workflow id
# query param: status = RUNNING | BLOCKED | SUCCEEDED | FAILED | CANCELLED
# query param: from, to = scheduled time range
# query param: limit = 1..200 (default 50)
# query param: cursor = opaque value from nextCursor
GET /v1/workflows/0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a/runs HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "items": [
    {
      "workflowRunId": "0192f3b6-8e9f-7a0b-8c1d-2e3f4a5b6c7a",
      "status": "SUCCEEDED",
      "scheduledAt": "2026-10-14T09:30:00Z",
      "finishedAt": "2026-10-14T09:30:02Z",
      "counts": {
        "total": 3,
        "succeeded": 3,
        "failed": 0,
        "cancelled": 0,
        "skipped": 0
      }
    }
  ],
  "nextCursor": "eyJrIjoiMjAyNi0xMC0xNFQwOTozMDowMFoifQ"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The workflow does not exist. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The workflow does not exist."}
```

**Background effect:** None.

</details>

<details>
<summary><b>56. Get a workflow run</b> &nbsp; <code>GET /v1/workflow-runs/{id}</code></summary>

**Request**

```http
# path param:  id = workflow run id
GET /v1/workflow-runs/0192f3b6-8e9f-7a0b-8c1d-2e3f4a5b6c7a HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "workflowRunId": "0192f3b6-8e9f-7a0b-8c1d-2e3f4a5b6c7a",
  "workflowId": "0192f3b5-3d4e-7a5b-9c6d-7e8f9a0b1c7a",
  "version": 2,
  "status": "RUNNING",
  "scheduledAt": "2026-10-14T09:30:00Z",
  "startedAt": "2026-10-14T09:30:00.084Z",
  "finishedAt": null,
  "counts": {
    "total": 3,
    "succeeded": 1,
    "failed": 0,
    "cancelled": 0,
    "skipped": 0
  },
  "tasks": [
    {
      "taskKey": "login",
      "runId": "0192f3b6-9e8d-7c6b-8a59-4f3e2d1c0b7a",
      "status": "SUCCEEDED",
      "attempt": 1,
      "dependsOn": []
    },
    {
      "taskKey": "createOrder",
      "runId": "0192f3b6-9f0a-7b1c-9d2e-3f4a5b6c7d7a",
      "status": "RUNNING",
      "attempt": 1,
      "dependsOn": [
        "login"
      ]
    },
    {
      "taskKey": "notify",
      "runId": "0192f3b6-a0b1-7c2d-8e3f-4a5b6c7d8e7a",
      "status": "WAITING",
      "attempt": 0,
      "dependsOn": [
        "createOrder"
      ]
    }
  ]
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The workflow run does not exist. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The workflow run does not exist."}
```

**Background effect:** None.

</details>

<details>
<summary><b>57. Cancel a workflow run</b> &nbsp; <code>POST /v1/workflow-runs/{id}/cancel</code></summary>

**Request**

```http
# path param:  id = workflow run id
POST /v1/workflow-runs/0192f3b6-8e9f-7a0b-8c1d-2e3f4a5b6c7a/cancel HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "workflowRunId": "0192f3b6-8e9f-7a0b-8c1d-2e3f4a5b6c7a",
  "status": "CANCELLED",
  "scheduledAt": "2026-10-14T09:30:00Z",
  "finishedAt": "2026-10-14T09:30:00.084Z",
  "counts": {
    "total": 3,
    "succeeded": 3,
    "failed": 0,
    "cancelled": 0,
    "skipped": 0
  }
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The workflow run does not exist. |
| `409` | `run-final` | The workflow run already finished with status SUCCEEDED. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The workflow run does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/run-final", "title": "Already finished", "status": 409, "detail": "The workflow run already finished with status SUCCEEDED."}
```

**Background effect:** Waiting and queued tasks are cancelled; running tasks get a cancel message and their calls are aborted.

</details>

<details>
<summary><b>58. Retry a failed workflow run</b> &nbsp; <code>POST /v1/workflow-runs/{id}/retry</code></summary>

**Request**

```http
# path param:  id = workflow run id
POST /v1/workflow-runs/0192f3b6-8e9f-7a0b-8c1d-2e3f4a5b6c7a/retry HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json

{
  "from": "FAILED_TASKS"
}
```

**Success: `202 Accepted`**

```http
HTTP/1.1 202 Accepted
Content-Type: application/json

{
  "workflowRunId": "0192f3b6-8e9f-7a0b-8c1d-2e3f4a5b6c7a",
  "status": "RUNNING",
  "requeuedTasks": [
    "createOrder",
    "notify"
  ]
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The workflow run does not exist. |
| `409` | `invalid-state` | Only a FAILED or BLOCKED workflow run can be retried. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The workflow run does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/invalid-state", "title": "Invalid state", "status": 409, "detail": "Only a FAILED or BLOCKED workflow run can be retried."}
```

**Background effect:** Failed and skipped tasks are re-queued with new attempt series; outputs of tasks that succeeded are reused.

</details>

### 3.8 Runs, DLQ and stats

<details>
<summary><b>59. List runs</b> &nbsp; <code>GET /v1/runs</code></summary>

**Request**

```http
# query param: status = WAITING | QUEUED | RUNNING | DELAYED | SUCCEEDED | DEAD_LETTERED | CANCELLED | SKIPPED
# query param: job = job id
# query param: endpoint = endpoint name
# query param: from, to = scheduled time range (within the last 7 days)
# query param: limit = 1..200 (default 50)
# query param: cursor = opaque value from nextCursor
GET /v1/runs?status=RUNNING&endpoint=orders-create HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "items": [
    {
      "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
      "jobId": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
      "status": "SUCCEEDED",
      "attempt": 2,
      "scheduledAt": "2026-10-14T09:30:00Z",
      "finishedAt": "2026-10-14T09:30:31Z",
      "httpStatus": 201,
      "latencyMs": 84
    }
  ],
  "nextCursor": "eyJrIjoiMjAyNi0xMC0xNFQwOTozMDowMFoifQ"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "from", "code": "out-of-range"}]}
```

**Background effect:** None. Reads every cell's replica in parallel and merges by scheduled time.

</details>

<details>
<summary><b>60. Get a run</b> &nbsp; <code>GET /v1/runs/{runId}</code></summary>

**Request**

```http
# path param:  runId = run id
GET /v1/runs/0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9 HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
  "jobId": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
  "workflowRunId": null,
  "taskKey": null,
  "status": "SUCCEEDED",
  "attempt": 2,
  "trigger": "SCHEDULE",
  "scheduledAt": "2026-10-14T09:30:00Z",
  "startedAt": "2026-10-14T09:30:31Z",
  "finishedAt": "2026-10-14T09:30:31Z",
  "httpStatus": 201,
  "latencyMs": 84,
  "errorClass": null,
  "error": null,
  "attempts": [
    {
      "attempt": 1,
      "outcome": "FAILED_RETRYING",
      "httpStatus": 503,
      "latencyMs": 40,
      "finishedAt": "2026-10-14T09:30:00.084Z"
    },
    {
      "attempt": 2,
      "outcome": "SUCCEEDED",
      "httpStatus": 201,
      "latencyMs": 84,
      "finishedAt": "2026-10-14T09:30:31Z"
    }
  ]
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The run does not exist. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The run does not exist."}
```

**Background effect:** None.

</details>

<details>
<summary><b>61. Cancel a run</b> &nbsp; <code>POST /v1/runs/{runId}/cancel</code></summary>

**Request**

```http
# path param:  runId = run id
POST /v1/runs/0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9/cancel HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
  "jobId": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
  "status": "CANCELLED",
  "attempt": 2,
  "scheduledAt": "2026-10-14T09:30:00Z",
  "finishedAt": "2026-10-14T09:30:31Z",
  "httpStatus": null,
  "latencyMs": null
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The run does not exist. |
| `409` | `run-final` | The run already finished with status SUCCEEDED. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The run does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/run-final", "title": "Already finished", "status": 409, "detail": "The run already finished with status SUCCEEDED."}
```

**Background effect:** A waiting, queued or delayed run is deleted together with its unpublished outbox row. A running run is flagged and a cancel message is sent on `kairos.run.cancel`; the executor aborts the call and reports `CANCELLED`.

</details>

<details>
<summary><b>62. List DLQ entries</b> &nbsp; <code>GET /v1/dlq</code></summary>

**Request**

```http
# query param: status = OPEN | ACKNOWLEDGED | REDRIVEN | DISCARDED
# query param: endpoint = endpoint name
# query param: reason = error class, for example HTTP_5XX
# query param: limit = 1..200 (default 50)
# query param: cursor = opaque value from nextCursor
GET /v1/dlq?status=OPEN&endpoint=orders-create HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "items": [
    {
      "dlqId": "0192f3c7-1a2b-7c3d-8e4f-5a6b7c8d9ec9",
      "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
      "jobId": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
      "workflowRunId": null,
      "taskKey": null,
      "endpoint": "orders-create",
      "reason": "HTTP_5XX",
      "lastHttpStatus": 503,
      "lastError": "Service Unavailable",
      "attempt": 6,
      "status": "OPEN",
      "assignee": null,
      "notes": null,
      "createdAt": "2026-10-14T09:45:12Z",
      "resolvedAt": null
    }
  ],
  "nextCursor": "eyJrIjoiMjAyNi0xMC0xNFQwOTozMDowMFoifQ"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "status", "code": "enum"}]}
```

**Background effect:** None.

</details>

<details>
<summary><b>63. Get a DLQ entry</b> &nbsp; <code>GET /v1/dlq/{id}</code></summary>

**Request**

```http
# path param:  id = DLQ entry id
GET /v1/dlq/0192f3c7-1a2b-7c3d-8e4f-5a6b7c8d9ec9 HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "dlqId": "0192f3c7-1a2b-7c3d-8e4f-5a6b7c8d9ec9",
  "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
  "jobId": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
  "workflowRunId": null,
  "taskKey": null,
  "endpoint": "orders-create",
  "reason": "HTTP_5XX",
  "lastHttpStatus": 503,
  "lastError": "Service Unavailable",
  "attempt": 6,
  "status": "OPEN",
  "assignee": null,
  "notes": null,
  "createdAt": "2026-10-14T09:45:12Z",
  "resolvedAt": null
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The DLQ entry does not exist. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The DLQ entry does not exist."}
```

**Background effect:** None.

</details>

<details>
<summary><b>64. Acknowledge a DLQ entry</b> &nbsp; <code>POST /v1/dlq/{id}/acknowledge</code></summary>

**Request**

```http
# path param:  id = DLQ entry id
POST /v1/dlq/0192f3c7-1a2b-7c3d-8e4f-5a6b7c8d9ec9/acknowledge HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json

{
  "assignee": "asha@example.com",
  "notes": "Orders service returned 503 during deploy."
}
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "dlqId": "0192f3c7-1a2b-7c3d-8e4f-5a6b7c8d9ec9",
  "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
  "jobId": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
  "workflowRunId": null,
  "taskKey": null,
  "endpoint": "orders-create",
  "reason": "HTTP_5XX",
  "lastHttpStatus": 503,
  "lastError": "Service Unavailable",
  "attempt": 6,
  "status": "ACKNOWLEDGED",
  "assignee": "asha@example.com",
  "notes": "Orders service returned 503 during deploy.",
  "createdAt": "2026-10-14T09:45:12Z",
  "resolvedAt": null
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `404` | `not-found` | The DLQ entry does not exist. |
| `409` | `dlq-resolved` | The entry was already redriven or discarded. |

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The DLQ entry does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/dlq-resolved", "title": "Already resolved", "status": 409, "detail": "The entry was already redriven or discarded."}
```

**Background effect:** None.

</details>

<details>
<summary><b>65. Redrive a DLQ entry</b> &nbsp; <code>POST /v1/dlq/{id}/redrive</code></summary>

**Request**

```http
# path param:  id = DLQ entry id
POST /v1/dlq/0192f3c7-1a2b-7c3d-8e4f-5a6b7c8d9ec9/redrive HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json

{
  "mode": "ORIGINAL"
}
```

**Success: `202 Accepted`**

```http
HTTP/1.1 202 Accepted
Content-Type: application/json

{
  "dlqId": "0192f3c7-1a2b-7c3d-8e4f-5a6b7c8d9ec9",
  "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
  "jobId": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
  "workflowRunId": null,
  "taskKey": null,
  "endpoint": "orders-create",
  "reason": "HTTP_5XX",
  "lastHttpStatus": 503,
  "lastError": "Service Unavailable",
  "attempt": 6,
  "status": "REDRIVEN",
  "assignee": null,
  "notes": null,
  "createdAt": "2026-10-14T09:45:12Z",
  "resolvedAt": "2026-10-14T09:30:00.084Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | mode must be ORIGINAL or CURRENT. |
| `404` | `not-found` | The DLQ entry does not exist. |
| `409` | `dlq-resolved` | The entry was already redriven or discarded. |
| `409` | `endpoint-suspended` | Resume endpoint orders-create before redriving. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "mode must be ORIGINAL or CURRENT.", "errors": [{"field": "mode", "code": "enum"}]}

HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The DLQ entry does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/dlq-resolved", "title": "Already resolved", "status": 409, "detail": "The entry was already redriven or discarded."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/endpoint-suspended", "title": "Endpoint suspended", "status": 409, "detail": "Resume endpoint orders-create before redriving."}
```

**Background effect:** The run starts a new attempt series under the same run id, so the target sees the same `Idempotency-Key`, and continues from the fence stored on the entry, so messages of the dead-lettered run can never claim it. `ORIGINAL` uses the pinned configuration, `CURRENT` the latest job and endpoint versions. A blocked workflow run resumes.

</details>

<details>
<summary><b>66. Discard a DLQ entry</b> &nbsp; <code>POST /v1/dlq/{id}/discard</code></summary>

**Request**

```http
# path param:  id = DLQ entry id
POST /v1/dlq/0192f3c7-1a2b-7c3d-8e4f-5a6b7c8d9ec9/discard HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json

{
  "reason": "Order was created manually."
}
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "dlqId": "0192f3c7-1a2b-7c3d-8e4f-5a6b7c8d9ec9",
  "runId": "0192f3a2-0b11-7e3a-8c55-91d0a7e6b2c9",
  "jobId": "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9",
  "workflowRunId": null,
  "taskKey": null,
  "endpoint": "orders-create",
  "reason": "HTTP_5XX",
  "lastHttpStatus": 503,
  "lastError": "Service Unavailable",
  "attempt": 6,
  "status": "DISCARDED",
  "assignee": null,
  "notes": null,
  "createdAt": "2026-10-14T09:45:12Z",
  "resolvedAt": "2026-10-14T09:30:00.084Z"
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |
| `404` | `not-found` | The DLQ entry does not exist. |
| `409` | `dlq-resolved` | The entry was already redriven or discarded. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "reason", "code": "required"}]}

HTTP/1.1 404 Not Found
Content-Type: application/problem+json

{"type": "/errors/not-found", "title": "Not found", "status": 404, "detail": "The DLQ entry does not exist."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/dlq-resolved", "title": "Already resolved", "status": 409, "detail": "The entry was already redriven or discarded."}
```

**Background effect:** The entry is closed and kept for 7 days. In a blocked workflow run, the dependents of the task are marked `UPSTREAM_FAILED`.

</details>

<details>
<summary><b>67. Redrive DLQ entries in bulk</b> &nbsp; <code>POST /v1/dlq:redrive</code></summary>

**Request**

```http
POST /v1/dlq:redrive HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
Content-Type: application/json
Idempotency-Key: 9b1d6f2e-5c3a-4e8f-a1b2-c3d4e5f6a7b8

{
  "filter": {
    "endpoint": "orders-create",
    "reason": "HTTP_5XX",
    "from": "2026-10-14T00:00:00Z",
    "to": "2026-10-14T12:00:00Z"
  },
  "mode": "ORIGINAL",
  "dryRun": false
}
```

**Success: `202 Accepted`**

```http
HTTP/1.1 202 Accepted
Content-Type: application/json

{
  "matched": 1820,
  "scheduled": 1820,
  "dryRun": false
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | At least one filter field is required. |
| `409` | `idempotency-key-reused` | This Idempotency-Key was already used with a different request body. |
| `409` | `endpoint-suspended` | Resume endpoint orders-create before redriving. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "At least one filter field is required.", "errors": [{"field": "filter", "code": "required"}]}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/idempotency-key-reused", "title": "Idempotency key reused", "status": 409, "detail": "This Idempotency-Key was already used with a different request body."}

HTTP/1.1 409 Conflict
Content-Type: application/problem+json

{"type": "/errors/endpoint-suspended", "title": "Endpoint suspended", "status": 409, "detail": "Resume endpoint orders-create before redriving."}
```

**Background effect:** Matching entries are redriven as `DELAYED` runs released by the delayed poller at 100/s. `dryRun: true` only returns the count.

</details>

<details>
<summary><b>68. Get team stats</b> &nbsp; <code>GET /v1/stats</code></summary>

**Request**

```http
# query param: window = 1h..7d (default 24h)
GET /v1/stats?window=24h HTTP/1.1
Host: kairos.internal.example.com
Authorization: Bearer <access token or API key>
```

**Success: `200 OK`**

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "window": "24h",
  "attempts": 1730000,
  "successRate": 0.9981,
  "p50LatencyMs": 61,
  "p95LatencyMs": 180,
  "deadLettered": 42,
  "openDlq": 17,
  "byEndpoint": [
    {
      "endpoint": "orders-create",
      "attempts": 912000,
      "successRate": 0.999,
      "p95LatencyMs": 140
    }
  ]
}
```

**Errors**

| Status | Code | Message |
|---|---|---|
| `400` | `validation` | One or more fields are invalid. |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json

{"type": "/errors/validation", "title": "Invalid request", "status": 400, "detail": "One or more fields are invalid.", "errors": [{"field": "window", "code": "range"}]}
```

**Background effect:** None. Served from the per-minute stats tables.

</details>

## 4. Background processing

### 4.1 Jobs and parameters

| Job | Service | Runs | Parameters (defaults) | What it does |
|---|---|---|---|---|
| Shard lease manager | kairos-watcher | Every 5s | Lease TTL 15s; fair share `ceil(cell shard count / live watchers)`; release surplus above fair share + 2 | Acquires, renews and releases shard leases in each cell |
| Fire poller | kairos-watcher | Every 1s ±20% per group of 8 shards, and on `NOTIFY` | Look-ahead 5s; ≤ 250 schedules per shard per cycle; misfire threshold 60s; pauses while the oldest due outbox row is > 10s old | Claims due schedules; inserts runs (and workflow runs) with outbox rows; advances `next_fire_at` |
| Delayed poller | kairos-watcher | Every 1s | ≤ 250 runs per shard; backfill 100/s per job and 1K/s overall; redrive 100/s | Moves due `DELAYED` runs (retries, deferrals, backfill, redrive) to `QUEUED` with outbox rows. A shared Redis one-second window applies delayed-dispatch caps; excess runs advance `due_at` to the next second in the cell transaction. Redis unavailability pauses dispatch rather than bypassing limits. |
| Outbox relay | kairos-watcher | Every 100 ms per shard group | Batch 2K rows; Kafka `acks=all`, idempotent producer; optional dispatch cap per second | Publishes due outbox rows, deletes only acknowledged rows, and advances failed rows' `available_at` for retry |
| Lease sweeper | kairos-watcher | Every 5s | Margin 30s after `lease_until`; max 3 infrastructure re-queues | Recovers runs whose executor died, using the Redis lock and sent marker |
| Stuck-queued sweeper | kairos-watcher | Every 1 min | `QUEUED` older than 10 min | Safety net: re-dispatches and raises an alert |
| Redis failover monitor | kairos-watcher | Every 2s | Quarantine 310s | Detects a Redis primary change; missing sent markers then count as unknown outcomes |
| Auto-suspend evaluator | kairos-watcher (one instance) | Every 15s | ≥ 50 dead letters in 5 min, or ≥ 80% failures over ≥ 200 attempts; 10-minute cool-down | Suspends failing endpoints, audits and alerts |
| Review-timeout sweeper | kairos-watcher (one instance) | Every 60s | Review timeout 24h | Fails dependents of `BLOCKED` workflow runs past their deadline |
| Purger | kairos-watcher (one instance) | Every 1h | Retention 7 days; idempotency records 24h; expired sessions | Deletes resolved DLQ entries, deleted definitions, expired records |

The executor runs the four execution gates in order. It acquires `kairos:lock:run:{runId}` with a TTL of endpoint timeout + 10s, then conditionally changes the matching uncancelled `QUEUED` row to `RUNNING`, increments its fence, and records `owner_node` and `lease_until` in the run's cell transaction. A zero-row claim is stale, duplicate or cancelled and is acknowledged without a call. Immediately before request bytes leave, `SendObserver` requires the owned lock to have at least timeout + 2s remaining, then writes `kairos:sent:{runId}:{attempt}:{infraAttempt}` with its 48h TTL. Long calls renew Redis and the matching cell lease at half the lock TTL. T-243 supplies the `ExecutionAction`; it returns only after a result is durably published or the run is durably re-queued. The lock is released after that return. An action failure leaves the lock and database lease to expire for the lease sweeper.

The Redis pacing reservation precedes the cell commit. If that transaction rolls back, its one-second slot is unused; the cap remains safe and the next poll retries the run. Delayed runs themselves are never held in watcher memory.

Recovery sweeps run in the same generation-fenced cell transactions as other shard pollers. The lease sweeper claims at most 250 `RUNNING` rows per shard whose lease expired more than 30 seconds ago. A held or unavailable Redis lock pauses recovery, as does an unavailable sent-marker read. With no marker and no failover quarantine, it records `EXECUTOR_LOST` in history and re-queues the same attempt with an incremented `infra_attempt` and fence; after three infrastructure re-queues it records a final dead letter. A present marker or active quarantine invokes the pinned endpoint's unknown-outcome policy and resolved retry policy. `REVIEW` writes a final `UNKNOWN_OUTCOME` dead letter; a retry keeps the run id as its idempotency key, advances `attempt`, and returns to `DELAYED`. Each transition, history record, and dispatch or dead event commits together in the cell. The stuck-queued sweeper claims at most 250 rows per shard older than ten minutes, advances their fences, updates `due_at`, and writes dispatch outbox rows. It emits `kairos_stuck_queued_redispatch_total` after commit.

Singleton watcher maintenance holds one PostgreSQL advisory lock on a dedicated control-database connection; the lock connection is closed and the lock released at shutdown, and a watcher with a lost connection must reacquire before running maintenance. The auto-suspend evaluator runs every 15 seconds using the T-103 defaults across recent `endpoint_health_minute` rows. It skips endpoints with `suspendPolicy.enabled=false` and endpoints whose `status_changed_at` is within the ten-minute cool-down. A suspension conditionally changes an `ACTIVE` endpoint to `SUSPENDED` with reason `AUTO_SUSPENDED` and writes the `system:auto-suspend` audit record in one control transaction. The evaluator reconciles active schedules to held status 3 across cells for every suspended endpoint, so a failure between the control commit and cell updates is repaired on the next pass. It emits `kairos_endpoint_suspensions_total` after a successful change.

The review-timeout sweeper runs each minute. For a `BLOCKED` workflow past `blocked_until`, it reads open task dead letters and the pinned workflow version's `dependsOn` graph, writes `UPSTREAM_FAILED` history for waiting descendant tasks, removes those rows from `run_inflight`, and returns the workflow to `RUNNING` so state processing can finish its remaining tasks. The hourly purger deletes resolved dead letters and deleted definitions older than seven days, idempotency records older than 24 hours, and expired sessions. Each delete claims at most 2,000 rows with `SKIP LOCKED`; a full batch is followed by a 50 ms pause before the next batch. Purge counters are `kairos_purger_rows_total` and `kairos_purger_batches_total`.

The Redis failover monitor samples the primary replication id every two seconds. A changed id extends `scheduler_flags.redis_marker_quarantine_until` to at least 310 seconds after detection. During that period, absent sent markers are treated as unknown outcomes because the former primary may have lost their writes. A first successful sample establishes the local baseline; an unavailable or replica response leaves it unchanged.

The outbox relay reads due rows in `outbox_id` order under the shard lease and holds their row locks while Kafka acknowledges them. It deletes acknowledged rows before the cell transaction commits; a crash before commit can republish a row. Failed rows remain in the outbox with a retry delay that grows from 1s to at most 30s as the row ages. `kairos.watcher.outbox.max-dispatches-per-second` is an optional per-watcher token bucket; `0` (the default) disables it. The relay reports each cell's backlog row count and oldest due age.

The node registry upserts each watcher every 5 seconds. A node is live while its heartbeat is newer than 15 seconds; older registrations are removed. In each cell a watcher targets the ceiling of that cell's shard range divided by live watchers, retaining at most two extra leases to reduce churn. A lease assignment and every release increments `generation`. Poll work stops three seconds before `lease_until`; each shard transaction locks and verifies its owner and generation through commit, preventing a takeover from overlapping a write. The shared poller schedules groups of up to eight shards at the registered interval with up to 200 ms of jitter, and records callback transaction duration. `READ_ONLY` cells are not leased; `DRAINING` cells remain leased until their shard range moves.
| Dispatch consumers | kairos-executor | Continuous | Topics `kairos.run.high/medium/low`, poll 250 / 150 / 100; in-flight high/low water 4K / 3K per pod; lock TTL = timeout + 10s; send guard 2s; sent marker TTL 48h; connect timeout 3s; whole exchange after the send cut off at the endpoint `timeoutMs`; response cap 256 KB; request body cap 1 MiB; no redirects; OAuth2 token-endpoint failures classified by the token endpoint's own status (401 not retried, 5xx retried) and a network failure there counts as not sent; breaker 50% over 100 calls, open 30s; drain 60s | Lock, claim, render, call, classify, publish result |
| Cancel listener | kairos-executor | Continuous | Topic `kairos.run.cancel`, one consumer group per pod | Aborts the matching in-flight call |
Each executor derives its cancel consumer group from its pod hostname, so Kafka broadcasts every cancel to every executor. A cancel applies when its fence is at least the active claim fence. The HTTP client accepts a per-call cancellation signal and aborts the active exchange; the executor publishes `CANCELLED` with the sent-marker state. A cancel that arrives after the exchange finishes has no effect. On SIGTERM, readiness changes to `REFUSING_TRAFFIC`, dispatch polling stops, and the executor waits up to 60 seconds for active work. Calls that have not crossed the send cut-off are cancelled and transactionally returned to `QUEUED` with a new fence and a dispatch outbox row. The owning tier's poll thread commits the corresponding Kafka offset only after that transaction succeeds and before closing its consumer; a failed requeue leaves the offset uncommitted for replay. The send cut-off and drain requeue are serialized per active call, so a request cannot begin after its run was returned to the queue. Any sent call still active at the deadline is aborted as an unknown outcome; its run is settled from the result or lease recovery.
| Result settler | kairos-state | Continuous | Topic `kairos.run.result`, ≤ 1K records per batch, one transaction per cell; retries 5, base 30s, cap 10 min, max age 24h; auto-pause after 3 dead runs | Writes history, schedules retries, dead-letters, advances workflows, writes follow-up outbox rows |
| Stats aggregator | kairos-state | Every 10s | Per minute and node | Flushes `endpoint_health_minute` and `team_stats_minute` |
| Signing-key expiry | kairos-api | Every 60s | Overlap 24h; batch 100 teams; optimistic replacement per keyset | Removes expired non-primary signing keys and audits the change; safe across API replicas |
| Partition manager | kairos-maintenance | Daily (CronJob) | Create partitions 3 days ahead; drop after 7 days (audit 90 days) | Manages `run_history`, `task_outputs` and `audit_log` partitions |
| Operator commands | kairos-maintenance | On demand | `team set-quota`, `user disable`, `user reset-password` | Audited operator actions |

### 4.2 Kafka topics

| Topic | Key | Partitions | Retention | Producer → consumer |
|---|---|---|---|---|
| `kairos.run.high` / `.medium` / `.low` | target id | 24 / 48 / 24 | 24h | outbox relay → executors |
| `kairos.run.result` | target id (taken from the dispatch) | 48 | 24h | executors → kairos-state |
| `kairos.run.cancel` | run id | 6 | 1h | outbox relay → every executor |
| `kairos.run.completed` | definition id (job or workflow id) | 12 | 3 days | outbox relay → subscribers (opt-in per team) |
| `kairos.run.dead` | definition id (job or workflow id) | 6 | 14 days | outbox relay → alerting |

Replication factor 3, `min.insync.replicas = 2`, Protobuf payloads. A dispatch message carries the run id, fence, attempt, pinned endpoint version and a snapshot of the request values, never secrets.

Message contracts (package `kairos.v1`, `libs/kairos-proto`). Ids are 16 bytes, big-endian. Enum numbers equal the smallint codes of section 2, and 0 means not set. `infra_attempt` starts at 0. The W3C `traceparent` travels in a Kafka header, and the dispatch also carries it as a field.

| Message | Topic | Fields |
|---|---|---|
| `Dispatch` | `kairos.run.high/medium/low` | run id, shard, fence, attempt, infra attempt, team id, target type, definition id and version, target id, task key, endpoint id and version, priority, timeout, scheduled and due times, request snapshot, trace parent |
| `RequestSnapshot` | (inside `Dispatch`) | rendered path params, query and headers (ordered name/value pairs), body bytes, names of the secrets the call needs, sensitive upstream outputs sealed with the team's `OUTPUTS` keyset |
| `Result` | `kairos.run.result` | run id, shard, fence, attempt, infra attempt, call outcome, error class, HTTP status, latency, started and finished times, executor id, redacted error (≤ 512), redacted response snippet (≤ 1 KB), non-sensitive outputs, sealed sensitive outputs, sent-marker flag, retry-after |
| `Cancel` | `kairos.run.cancel` | run id, shard, fence, reason (`REQUESTED`, `DEFINITION_CANCELLED`, `WORKFLOW_FAIL_FAST`), requested time |
| `Completed` | `kairos.run.completed` | run id, shard, team id, target type, definition id and version, target id, task key, trigger, scheduled time, final outcome, error class, HTTP status, attempts, started and finished times, latency; no outputs |
| `Dead` | `kairos.run.dead` | DLQ id, run id, shard, team id, target type, definition id and version, target id, task key, endpoint id and version, priority, scheduled time, attempt, infra attempt, reason, last HTTP status, dead-letter time; no error text or response content |

The executor reports a call outcome, and `kairos-state` maps it to the run outcome:
- `SUCCEEDED`, `FAILED` and `UNKNOWN` (the request may have been sent but no response arrived) are settled with the retry policy and the `unknownOutcomePolicy`.
- `CANCELLED` and `SKIPPED` (past `max_lateness_ms`) settle directly.
- `DEFERRED` (rate limit or open breaker; no call made) delays the run without using an attempt.

Compatibility: field and enum numbers are never reused, and removed fields are reserved by number and name. The build fails when the contracts break the released descriptor set in `libs/kairos-proto/compat/`.

### 4.3 Redis keys

| Key | Value and TTL | Used by |
|---|---|---|
| `kairos:lock:run:{runId}` | Owner token (fence, node, random); TTL = timeout + 10s | Executor (take, release), lease sweeper (check) |
| `kairos:sent:{runId}:{attempt}:{infraAttempt}` | `1`; TTL 48h | Executor (write), lease sweeper (check) |
| `kairos:rl:{bucket}` | Token bucket | API rate limits: login per IP and account, signup per IP, member add and team create per user, team quotas |
| `kairos:pace:delayed:{epochSecond}` | Hash counters for delayed dispatches by team, backfill job, all backfills and all redrives; TTL 10s | Delayed poller on all watcher instances |
