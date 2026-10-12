# Kairos LLD Part 01: Database (PostgreSQL 18)

Back to [LLD index](../LLD.md).

## 1. Conventions

- **Databases.** One control database and N cell databases (HLD §6). Both use schema `scheduler`. Every object is created by the `sched_migrator` role through Flyway.
- **Identifiers.** UUIDv7 (time-ordered) is generated in the application by `Uuid7`, so a batch knows its run ids before it inserts them.
  - **Shard-embedded ids:** `run_id`, `workflow_run_id`, `dlq_id` and `outbox_id` carry the `shard_id` in the low 8 bits of `rand_b`, leaving 66 random bits. `Shards.of(id)` routes any of them to its cell with no lookup.
  - Job and workflow ids are plain UUIDv7, and their shard is `murmur3(id) mod 256`.
  - Control-plane tables default to `uuidv7()` for rows the API creates.
- **Timestamps.** `timestamptz`, always UTC. **Database `now()` is the clock** for every scheduling comparison.
- **Enumerations.** Hot and huge tables store `smallint` codes (fixed in §2, mirrored by Java enums with explicit codes and tested both ways). Control tables use `text` with `CHECK` constraints.
- **JSON.** Specs are stored as `jsonb`, but only after strict server-side validation into typed records (part 05). The database never accepts raw client JSON.
- **Arrays across JDBC.** Timestamps are passed as `bigint` epoch microseconds and converted by `scheduler.ts(bigint)`, because PgJDBC array support for `java.time` types is limited.
- **Size limits** are enforced in the API and repeated as `CHECK` constraints in the database.

## 2. Code tables

| Code | `run state` | `outcome` | `trigger` | `target_type` |
|---|---|---|---|---|
| 1 | WAITING | SUCCEEDED | SCHEDULE | JOB |
| 2 | QUEUED | FAILED_RETRYING | IMMEDIATE | WORKFLOW_TASK |
| 3 | RUNNING | DEAD_LETTERED | MANUAL | |
| 4 | DELAYED | CANCELLED | BACKFILL | |
| 5 | | SKIPPED | REDRIVE | |
| 6 | | UPSTREAM_FAILED | | |
| 7 | | EXECUTOR_LOST | | |

**`error_class` codes:**

| Code | Class | Code | Class | Code | Class |
|---|---|---|---|---|---|
| 0 | NONE | 9 | RENDER_ERROR | 18 | POISON |
| 1 | CONNECTION | 10 | POLICY_BLOCKED | 19 | MAX_AGE_EXCEEDED |
| 2 | TIMEOUT | 11 | CONFIG_ERROR | 20 | CANCELLED |
| 3 | HTTP_THROTTLED | 12 | DEPENDENCY_UNAVAILABLE | 21 | MISSED_SLA |
| 4 | HTTP_5XX | 13 | DEFERRED_RATE_LIMIT | 22 | UPSTREAM_FAILED |
| 5 | HTTP_5XX_PERMANENT | 14 | DEFERRED_BREAKER_OPEN | 23 | DEFERRED_POOL_EXHAUSTED |
| 6 | HTTP_4XX | 15 | DEFERRED_ENDPOINT_SUSPENDED | 24 | UPSTREAM_SKIPPED |
| 7 | HTTP_REDIRECT | 16 | EXECUTOR_LOST | 25 | REVIEW_TIMEOUT |
| 8 | CONTRACT_ERROR | 17 | EXECUTOR_SHUTDOWN | 26 | UNKNOWN_OUTCOME |
| | | | | 27 | DEFERRED_LOCK_UNAVAILABLE (Redis down) |
| | | | | 28 | SEND_GUARD_ABORT |

**Priority tier** is derived from `priority`: 8–10 high, 5–7 medium, 1–4 low.

**`outbox.topic` codes:** 1 `run.high`, 2 `run.medium`, 3 `run.low`, 4 `run.cancel`, 5 `run.completed`, 6 `run.dead`, 7 `events` (workflow, job and endpoint events go to `run.completed` or `run.dead` by type).

## 3. DDL

Two Flyway locations, applied to different databases (HLD §6):

| Location | Applied to | Contents |
|---|---|---|
| `db/control` | The **control database** (one) | Tenants, API keys, keysets, secrets, CA bundles, endpoints and their versions, node registry, flags, API idempotency, audit log, cell map |
| `db/cell` | **Every cell database** (N), with Flyway placeholders `shardFrom` and `shardTo` | Definitions (jobs, workflows), schedules, `run_inflight`, **`outbox`**, `run_history`, `workflow_runs`, `task_outputs`, `dead_letters`, `job_health`, stats, shard leases |

- References from cell tables to control tables (`tenant_id`, `endpoint_id`) are **not** foreign keys, because they live in different databases. The API validates them on write. Endpoint deletion checks every cell for references.
- In single-database development mode, both locations are applied to the same database; no names collide.

### 3.1 Control database (`db/control/V1__control.sql`)

```sql
CREATE SCHEMA IF NOT EXISTS scheduler;
SET search_path = scheduler;

-- helper: epoch micros -> timestamptz (used for array parameters)
CREATE OR REPLACE FUNCTION ts(micros bigint) RETURNS timestamptz
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
  RETURN timestamptz 'epoch' + micros * interval '1 microsecond';

-- ===================== tenants, keys, crypto =====================
CREATE TABLE tenants (
  tenant_id        uuid PRIMARY KEY DEFAULT uuidv7(),
  name             text NOT NULL UNIQUE CHECK (name ~ '^[a-z][a-z0-9-]{2,62}$'),
  status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  quotas           jsonb NOT NULL,          -- {maxJobs, maxExecPerSec, maxInflight, backfillPerSec, redrivePerSec}
  egress_policy    jsonb NOT NULL,          -- {hosts:[], suffixes:[], cidrs:[], ports:[443,8443]}
  retry_default    jsonb,                   -- tenant-level RetryPolicy override (nullable)
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
  key_id           text PRIMARY KEY CHECK (key_id ~ '^[A-Za-z0-9]{16}$'),
  tenant_id        uuid NOT NULL REFERENCES tenants,
  name             text NOT NULL CHECK (length(name) BETWEEN 1 AND 64),
  secret_hash      bytea NOT NULL CHECK (octet_length(secret_hash) = 32),  -- HMAC-SHA256(pepper, secret)
  pepper_version   smallint NOT NULL,
  scopes           text[] NOT NULL CHECK (cardinality(scopes) BETWEEN 1 AND 16),
  created_by       text NOT NULL,           -- key_id of creator or 'platform-admin'
  created_at       timestamptz NOT NULL DEFAULT now(),
  expires_at       timestamptz,
  revoked_at       timestamptz,
  last_used_at     timestamptz
);
CREATE INDEX api_keys_tenant ON api_keys (tenant_id) WHERE revoked_at IS NULL;

-- Tink keysets per tenant and purpose, encrypted with the KMS KEK (never stored in clear)
CREATE TABLE tenant_keysets (
  tenant_id        uuid NOT NULL REFERENCES tenants,
  purpose          text NOT NULL CHECK (purpose IN ('SECRETS','OUTPUTS','SIGNING')),
  encrypted_keyset bytea NOT NULL,
  kek_uri          text NOT NULL,           -- gcp-kms://... or local-dev://...
  updated_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, purpose)
);

-- target credentials, sealed with the tenant SECRETS keyset (AAD = tenant|name|version)
CREATE TABLE tenant_secrets (
  tenant_id        uuid NOT NULL REFERENCES tenants,
  name             text NOT NULL CHECK (name ~ '^[a-z][a-z0-9-]{1,62}$'),
  version          int  NOT NULL,
  kind             text NOT NULL CHECK (kind IN ('BASIC','BEARER','API_KEY','OAUTH2_CLIENT')),
  ciphertext       bytea NOT NULL CHECK (octet_length(ciphertext) <= 16384),
  created_at       timestamptz NOT NULL DEFAULT now(),
  disabled_at      timestamptz,
  PRIMARY KEY (tenant_id, name, version)
);

CREATE TABLE tenant_ca_bundles (            -- public CA certificates for internal TLS
  tenant_id        uuid NOT NULL REFERENCES tenants,
  name             text NOT NULL,
  pem              text NOT NULL CHECK (length(pem) <= 65536),
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, name)
);

-- ===================== definitions =====================
CREATE TABLE api_endpoints (
  endpoint_id      uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id        uuid NOT NULL REFERENCES tenants,
  name             text NOT NULL CHECK (name ~ '^[a-z][a-z0-9-]{1,62}$'),
  version          int  NOT NULL DEFAULT 1,
  status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','DISABLED')),
  status_reason    text,
  status_changed_at timestamptz,
  spec             jsonb NOT NULL CHECK (octet_length(spec::text) <= 65536),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);
CREATE TABLE api_endpoint_versions (       -- immutable; pinned by runs
  endpoint_id      uuid NOT NULL REFERENCES api_endpoints,
  version          int  NOT NULL,
  spec             jsonb NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  created_by       text NOT NULL,
  PRIMARY KEY (endpoint_id, version)
);

CREATE TABLE scheduler_flags (                 -- small cluster-wide flags, e.g. the Redis failover quarantine
  name             text PRIMARY KEY CHECK (name IN ('redis_marker_quarantine_until')),
  value_ts         timestamptz,
  updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE scheduler_nodes (
  node_id          text PRIMARY KEY,             -- {role}-{pod}-{startMillis}
  roles            text NOT NULL,
  version          text NOT NULL,
  started_at       timestamptz NOT NULL,
  heartbeat_at     timestamptz NOT NULL
);
CREATE INDEX scheduler_nodes_heartbeat ON scheduler_nodes (heartbeat_at);

-- ===================== API support =====================
CREATE TABLE api_idempotency (
  tenant_id        uuid NOT NULL,
  idem_key         text NOT NULL CHECK (idem_key ~ '^[A-Za-z0-9_-]{1,64}$'),
  request_hash     bytea NOT NULL,               -- SHA-256 of method + path + canonical body
  status           smallint NOT NULL,            -- 1 IN_PROGRESS, 2 DONE
  http_status      smallint,
  response_body    jsonb,
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, idem_key)
);
CREATE INDEX api_idempotency_created ON api_idempotency (created_at);

CREATE TABLE audit_log (
  audit_id         uuid NOT NULL DEFAULT uuidv7(),
  at               timestamptz NOT NULL DEFAULT now(),
  tenant_id        uuid,
  actor            text NOT NULL,                -- key_id, 'system:auto-suspend', ...
  action           text NOT NULL,                -- ENDPOINT_SUSPENDED, DLQ_REDRIVE, KEY_REVOKED, AUTH_FAILED, ...
  resource_type    text NOT NULL,
  resource_id      text,
  details          jsonb,                        -- never secrets; validated allow-listed fields only
  PRIMARY KEY (at, audit_id)
) PARTITION BY RANGE (at);
CREATE TABLE audit_log_default PARTITION OF audit_log DEFAULT;

-- ===================== cell map (validated against static config at startup) =====================
CREATE TABLE cell_map (
  cell_id          smallint PRIMARY KEY,
  shard_from       smallint NOT NULL CHECK (shard_from BETWEEN 0 AND 255),
  shard_to         smallint NOT NULL CHECK (shard_to BETWEEN 0 AND 255),
  datasource_ref   text NOT NULL,                -- name of a datasource entry in configuration; never a URL or credentials
  status           text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DRAINING','READ_ONLY')),
  CHECK (shard_from <= shard_to)
);

-- (API rate limiting lives in Redis: Bucket4j + Lettuce)
```

### 3.2 Cell database (`db/cell/V1__cell.sql`)

```sql
CREATE SCHEMA IF NOT EXISTS scheduler;
SET search_path = scheduler;

-- helper: epoch micros -> timestamptz (used for array parameters)
CREATE OR REPLACE FUNCTION ts(micros bigint) RETURNS timestamptz
  LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
  RETURN timestamptz 'epoch' + micros * interval '1 microsecond';

CREATE TABLE jobs (
  job_id           uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id        uuid NOT NULL,            -- control-DB tenant (validated by the app)
  name             text NOT NULL CHECK (length(name) BETWEEN 1 AND 128),
  version          int  NOT NULL DEFAULT 1,
  status           text NOT NULL CHECK (status IN ('ACTIVE','PAUSED','AUTO_PAUSED','COMPLETED','FAILED','CANCELLED')),
  endpoint_id      uuid NOT NULL,            -- control-DB endpoint (validated by the app)
  spec             jsonb NOT NULL CHECK (octet_length(spec::text) <= 131072),
  created_by       text NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);
CREATE INDEX jobs_endpoint ON jobs (endpoint_id);
CREATE INDEX jobs_tenant_status ON jobs (tenant_id, status);
CREATE TABLE job_versions (
  job_id           uuid NOT NULL REFERENCES jobs,
  version          int  NOT NULL,
  spec             jsonb NOT NULL,          -- schedule, request values, retry override, priority, policies
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (job_id, version)
);

CREATE TABLE workflows (
  workflow_id      uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id        uuid NOT NULL,            -- control-DB tenant (validated by the app)
  name             text NOT NULL CHECK (length(name) BETWEEN 1 AND 128),
  version          int  NOT NULL DEFAULT 1,
  status           text NOT NULL CHECK (status IN ('ACTIVE','PAUSED','CANCELLED')),
  spec             jsonb NOT NULL CHECK (octet_length(spec::text) <= 524288),
  created_by       text NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, name)
);
CREATE TABLE workflow_versions (
  workflow_id      uuid NOT NULL REFERENCES workflows,
  version          int  NOT NULL,
  spec             jsonb NOT NULL,          -- tasks, edges, computed plan (topo order, children, indegree)
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (workflow_id, version)
);

-- ===================== schedules (narrow, hot) =====================
CREATE TABLE schedules (
  schedule_id      uuid PRIMARY KEY,         -- = job_id or workflow_id
  target_type      smallint NOT NULL,        -- 1 JOB, 3 WORKFLOW (definition kind)
  tenant_id        uuid NOT NULL,
  endpoint_id      uuid,                     -- jobs only; used to suspend/resume in bulk
  shard_id         smallint NOT NULL CHECK (shard_id BETWEEN 0 AND 255),
  status           smallint NOT NULL,        -- 1 ACTIVE, 2 PAUSED, 3 SUSPENDED_BY_ENDPOINT
  next_fire_at     timestamptz NOT NULL,
  kind             smallint NOT NULL,        -- 1 ONE_TIME, 2 CRON, 3 INTERVAL
  cron_expr        text,
  timezone         text,
  interval_ms      bigint CHECK (interval_ms IS NULL OR interval_ms >= 1000),
  spread_ms        int NOT NULL DEFAULT 0,
  dst_policy       smallint NOT NULL DEFAULT 1,
  misfire_policy   smallint NOT NULL DEFAULT 2,     -- 1 RUN_ALL, 2 RUN_LATEST, 3 SKIP
  max_catchup      smallint NOT NULL DEFAULT 100,
  definition_version int NOT NULL,
  priority         smallint NOT NULL CHECK (priority BETWEEN 1 AND 10),
  last_fired_local timestamp                 -- last fired local date-time (DST-safe iteration)
) WITH (fillfactor = 90);
CREATE INDEX schedules_due ON schedules (shard_id, next_fire_at) WHERE status = 1;
CREATE INDEX schedules_endpoint ON schedules (endpoint_id) WHERE endpoint_id IS NOT NULL;

-- ===================== live runs (small, very hot) =====================
CREATE TABLE run_inflight (
  shard_id         smallint NOT NULL,
  run_id           uuid NOT NULL,
  tenant_id        uuid NOT NULL,
  target_type      smallint NOT NULL,         -- 1 JOB, 2 WORKFLOW_TASK
  definition_id    uuid NOT NULL,             -- job_id or workflow_id
  definition_version int NOT NULL,
  target_id        uuid NOT NULL,             -- job_id (jobs) or workflow_run_id (tasks)
  task_key         text NOT NULL DEFAULT '',
  endpoint_id      uuid NOT NULL,
  endpoint_version int NOT NULL,
  scheduled_at     timestamptz NOT NULL,      -- occurrence identity (workflow run time for tasks)
  due_at           timestamptz NOT NULL,      -- intended dispatch time (spread, delay, backfill pacing)
  state            smallint NOT NULL,         -- 1 WAITING, 2 QUEUED, 3 RUNNING, 4 DELAYED
  attempt          smallint NOT NULL DEFAULT 1,
  series_start     smallint NOT NULL DEFAULT 1,      -- first attempt of the current (re)drive series
  series_started_at timestamptz NOT NULL,            -- maxAge anchor
  infra_attempt    smallint NOT NULL DEFAULT 0,
  deferrals        int NOT NULL DEFAULT 0,
  fence            int NOT NULL DEFAULT 1,
  owner_node       text,
  lease_until      timestamptz,
  remaining_deps   smallint,
  cancel_requested boolean NOT NULL DEFAULT false,
  priority         smallint NOT NULL,
  trigger          smallint NOT NULL,
  max_lateness_ms  int,
  PRIMARY KEY (shard_id, run_id)
) PARTITION BY HASH (shard_id);

DO $$ BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format('CREATE TABLE run_inflight_p%s PARTITION OF run_inflight
                    FOR VALUES WITH (MODULUS 16, REMAINDER %s)
                    WITH (fillfactor = 70,
                          autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 5000,
                          autovacuum_vacuum_insert_scale_factor = 0, autovacuum_vacuum_insert_threshold = 5000,
                          autovacuum_vacuum_cost_delay = 0)', i, i);
  END LOOP;
END $$;

CREATE UNIQUE INDEX run_inflight_occurrence ON run_inflight (shard_id, target_id, task_key, scheduled_at);
CREATE INDEX run_inflight_delayed  ON run_inflight (shard_id, due_at)      WHERE state = 4;
CREATE INDEX run_inflight_leases   ON run_inflight (shard_id, lease_until) WHERE state = 3;
CREATE INDEX run_inflight_queued   ON run_inflight (shard_id, due_at)      WHERE state = 2;   -- stuck-QUEUED safety net only

-- ===================== transactional outbox (small, very hot) =====================
CREATE TABLE outbox (
  shard_id         smallint NOT NULL,
  outbox_id        uuid NOT NULL,                -- UUIDv7 from the writer (time-ordered)
  topic            smallint NOT NULL,            -- codes in §2
  msg_key          bytea NOT NULL,               -- Kafka key (target_id)
  payload          bytea NOT NULL CHECK (octet_length(payload) <= 262144),   -- Protobuf
  trace_parent     text,                         -- W3C traceparent header value
  run_id           uuid,                         -- dispatch and cancel rows; lets cancel delete an unpublished dispatch
  available_at     timestamptz NOT NULL,         -- publish no earlier than this (the dispatch time)
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (shard_id, outbox_id)
) PARTITION BY HASH (shard_id);

DO $$ BEGIN
  FOR i IN 0..15 LOOP
    EXECUTE format('CREATE TABLE outbox_p%s PARTITION OF outbox
                    FOR VALUES WITH (MODULUS 16, REMAINDER %s)
                    WITH (fillfactor = 90,
                          autovacuum_vacuum_scale_factor = 0, autovacuum_vacuum_threshold = 5000,
                          autovacuum_vacuum_insert_scale_factor = 0, autovacuum_vacuum_insert_threshold = 5000,
                          autovacuum_vacuum_cost_delay = 0)', i, i);
  END LOOP;
END $$;
CREATE INDEX outbox_due ON outbox (shard_id, available_at);
CREATE INDEX outbox_run ON outbox (shard_id, run_id) WHERE run_id IS NOT NULL;

-- ===================== history (huge, append-only) =====================
CREATE TABLE run_history (
  scheduled_at     timestamptz NOT NULL,
  run_id           uuid NOT NULL,
  attempt          smallint NOT NULL,
  infra_attempt    smallint NOT NULL,
  tenant_id        uuid NOT NULL,
  target_type      smallint NOT NULL,
  definition_id    uuid NOT NULL,
  target_id        uuid NOT NULL,
  task_key         text NOT NULL DEFAULT '',
  endpoint_id      uuid NOT NULL,
  trigger          smallint NOT NULL,
  outcome          smallint NOT NULL,
  error_class      smallint NOT NULL DEFAULT 0,
  final            boolean NOT NULL,
  http_status      smallint,
  started_at       timestamptz,
  finished_at      timestamptz NOT NULL,
  latency_ms       int,
  executor_id      text,
  error            text CHECK (length(error) <= 512),
  response_snippet text CHECK (length(response_snippet) <= 1024),
  PRIMARY KEY (scheduled_at, run_id, attempt, infra_attempt)
) PARTITION BY RANGE (scheduled_at);
CREATE INDEX run_history_definition ON run_history (definition_id, scheduled_at DESC);
CREATE INDEX run_history_tenant     ON run_history (tenant_id, scheduled_at DESC);
CREATE INDEX run_history_wfrun      ON run_history (target_id) WHERE target_type = 2;
-- daily partitions are created by PartitionManager (§6); a DEFAULT partition catches stragglers and is alerted on
CREATE TABLE run_history_default PARTITION OF run_history DEFAULT;

-- ===================== workflows at runtime =====================
CREATE TABLE workflow_runs (
  workflow_run_id  uuid PRIMARY KEY,
  workflow_id      uuid NOT NULL REFERENCES workflows,
  workflow_version int NOT NULL,
  tenant_id        uuid NOT NULL,
  shard_id         smallint NOT NULL,
  scheduled_at     timestamptz NOT NULL,
  trigger          smallint NOT NULL,
  status           smallint NOT NULL,         -- 1 RUNNING, 2 BLOCKED, 3 SUCCEEDED, 4 FAILED, 5 CANCELLED
  total            smallint NOT NULL,
  succeeded        smallint NOT NULL DEFAULT 0,
  failed           smallint NOT NULL DEFAULT 0,  -- dead-lettered tasks
  cancelled        smallint NOT NULL DEFAULT 0,
  skipped          smallint NOT NULL DEFAULT 0,  -- SKIPPED + UPSTREAM_FAILED
  blocked_until    timestamptz,
  started_at       timestamptz NOT NULL DEFAULT now(),
  finished_at      timestamptz,
  UNIQUE (workflow_id, scheduled_at)
);
CREATE INDEX workflow_runs_blocked ON workflow_runs (blocked_until) WHERE status = 2;
CREATE INDEX workflow_runs_tenant  ON workflow_runs (tenant_id, scheduled_at DESC);

CREATE TABLE task_outputs (
  scheduled_at     timestamptz NOT NULL,        -- workflow run scheduled_at (partition key)
  workflow_run_id  uuid NOT NULL,
  task_key         text NOT NULL,
  tenant_id        uuid NOT NULL,
  outputs          jsonb NOT NULL DEFAULT '{}'::jsonb,   -- non-sensitive values
  sealed           bytea,                       -- XChaCha20-Poly1305 over sensitive JSON, AAD = tenant|wfr|task
  created_at       timestamptz NOT NULL DEFAULT now(),
  CHECK (octet_length(outputs::text) + coalesce(octet_length(sealed), 0) <= 24576),
  PRIMARY KEY (scheduled_at, workflow_run_id, task_key)
) PARTITION BY RANGE (scheduled_at);
CREATE TABLE task_outputs_default PARTITION OF task_outputs DEFAULT;

-- ===================== DLQ / triage =====================
CREATE TABLE dead_letters (
  dlq_id           uuid PRIMARY KEY DEFAULT uuidv7(),
  tenant_id        uuid NOT NULL,
  run_id           uuid NOT NULL,
  shard_id         smallint NOT NULL,
  scheduled_at     timestamptz NOT NULL,
  target_type      smallint NOT NULL,
  definition_id    uuid NOT NULL,
  definition_version int NOT NULL,
  target_id        uuid NOT NULL,
  task_key         text NOT NULL DEFAULT '',
  endpoint_id      uuid NOT NULL,
  endpoint_version int NOT NULL,
  attempt          smallint NOT NULL,
  infra_attempt    smallint NOT NULL,
  priority         smallint NOT NULL,
  reason           smallint NOT NULL,           -- error_class
  last_http_status smallint,
  last_error       text CHECK (length(last_error) <= 512),
  response_snippet text CHECK (length(response_snippet) <= 1024),
  status           smallint NOT NULL DEFAULT 1, -- 1 OPEN, 2 ACKNOWLEDGED, 3 REDRIVEN, 4 DISCARDED
  assignee         text,
  notes            text CHECK (length(notes) <= 2000),
  resolved_by      text,
  resolved_at      timestamptz,
  redrive_mode     smallint,                    -- 1 ORIGINAL, 2 CURRENT
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (run_id, attempt, infra_attempt)
);
CREATE INDEX dead_letters_triage   ON dead_letters (tenant_id, status, created_at DESC);
CREATE INDEX dead_letters_endpoint ON dead_letters (tenant_id, endpoint_id, status);
CREATE INDEX dead_letters_purge    ON dead_letters (resolved_at) WHERE status IN (3, 4);

-- ===================== health & stats =====================
CREATE TABLE job_health (                     -- sparse: rows exist only after recent failures
  job_id           uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  recent_mask      smallint NOT NULL DEFAULT 0, -- last 10 final outcomes, bit=1 failure
  recent_count     smallint NOT NULL DEFAULT 0, -- number of outcomes in the mask (<=10)
  consecutive_dead smallint NOT NULL DEFAULT 0,
  unhealthy_since  timestamptz,
  updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX job_health_unhealthy ON job_health (tenant_id) WHERE unhealthy_since IS NOT NULL;

CREATE TABLE endpoint_health_minute (         -- one row per (endpoint, minute, writer node): no write contention
  endpoint_id      uuid NOT NULL,
  minute           timestamptz NOT NULL,
  node_id          text NOT NULL,
  tenant_id        uuid NOT NULL,
  attempts         int NOT NULL,
  successes        int NOT NULL,
  retryable        int NOT NULL,
  non_retryable    int NOT NULL,
  dead_lettered    int NOT NULL,
  latency_sum_ms   bigint NOT NULL,
  latency_max_ms   int NOT NULL,
  PRIMARY KEY (endpoint_id, minute, node_id)
);
CREATE TABLE tenant_stats_minute (
  tenant_id        uuid NOT NULL,
  minute           timestamptz NOT NULL,
  node_id          text NOT NULL,
  attempts         int NOT NULL,
  successes        int NOT NULL,
  dead_lettered    int NOT NULL,
  latency_sum_ms   bigint NOT NULL,
  PRIMARY KEY (tenant_id, minute, node_id)
);

-- ===================== coordination =====================
CREATE TABLE shard_leases (
  shard_id         smallint PRIMARY KEY CHECK (shard_id BETWEEN 0 AND 255),
  owner_node       text,
  generation       bigint NOT NULL DEFAULT 0,
  lease_until      timestamptz NOT NULL DEFAULT 'epoch'
);
INSERT INTO shard_leases (shard_id) SELECT g FROM generate_series(${shardFrom}, ${shardTo}) g;   -- Flyway placeholders: this cell's shard range
```

**Notes:**
- **Why `(target_id, task_key, scheduled_at)` is unique instead of using partial indexes.** One non-partial unique index covers both job runs (`task_key = ''`) and workflow tasks. `ON CONFLICT` inference is then unambiguous on a partitioned table.
- **The outbox is deliberately tiny and short-lived.** Rows live about 5s (the lookahead) plus about 100 ms. It is hash-partitioned like `run_inflight`, so vacuum runs in parallel. `outbox_run` exists so a cancel can delete an unpublished dispatch.
- **HOT-friendliness.**
  - In `run_inflight`, the columns changed by a claim (`state`, `owner_node`, `lease_until`, `fence`) appear in partial indexes, so a claim is not a HOT update.
  - This is accepted because the table is small and stays in memory. The M2 benchmark measures the cost; the fallback (a separate `run_leases` table) is noted in part 02 §9.
  - `run_history` is insert-only.
- **`schedules.next_fire_at` is indexed**, so every fire is a non-HOT update. `fillfactor 90` and aggressive autovacuum (§5) keep bloat in check.

## 4. Roles and grants (`V2__roles.sql`, run by the migrator)

Passwords are never in migrations. Roles are created `NOLOGIN` here. Login and passwords are set by the platform: Terraform with Secret Manager on GCP, and `scripts/dev-keys.sh` locally from a generated `.env` that is gitignored.

| Role | Used by | Privileges (summary) | Session settings |
|---|---|---|---|
| `sched_migrator` | Flyway job only | Owns the schema (DDL) | — |
| `sched_maintainer` | `maintenance` CronJob | Member of `sched_migrator` for partition DDL; `DELETE` on purge tables | `statement_timeout = 10min` |
| `sched_api` | api | <ul><li>CRUD on definitions, keys, secrets, idempotency, DLQ</li><li>`INSERT/UPDATE/DELETE` on `run_inflight` (immediate runs, cancel, redrive)</li><li>`INSERT`, `DELETE` on `outbox` (immediate runs, cancels)</li><li>`SELECT` on history and stats</li><li>`INSERT` on audit and history</li></ul> | `statement_timeout = 5s` |
| `sched_watcher` | watcher | <ul><li>`SELECT/UPDATE/DELETE` on `schedules`</li><li>`SELECT/INSERT/UPDATE/DELETE` on `run_inflight`</li><li>`SELECT/INSERT/DELETE` on `outbox` (fire, delayed, recovery, and the **relay**)</li><li>`INSERT` on `workflow_runs`, `run_history`, `dead_letters`, `audit_log`</li><li>`UPDATE` on `shard_leases`, `api_endpoints(status, status_reason, status_changed_at)`, `workflow_runs`</li><li>`SELECT` on definitions and stats</li></ul> | `statement_timeout = 5s` |
| `sched_executor` | executor | <ul><li>`SELECT` on `run_inflight`</li><li>**Column-level** `UPDATE (state, owner_node, lease_until, fence)` on `run_inflight`</li><li>`SELECT` on `task_outputs`, `api_endpoint_versions`, `api_endpoints(endpoint_id, status, version)`, `tenants`, `tenant_keysets`, `tenant_secrets`, `tenant_ca_bundles`</li><li>`INSERT/UPDATE` on its own `scheduler_nodes` row</li></ul> | `statement_timeout = 2s` |
| `sched_state` | state | <ul><li>`SELECT/UPDATE/DELETE` on `run_inflight`</li><li>`INSERT` on `outbox`, `run_history`, `dead_letters`, `task_outputs`, `audit_log`</li><li>`ALL` on `job_health` and stats tables</li><li>`UPDATE (status, updated_at)` on `jobs`; `UPDATE (status)` on `schedules`</li><li>`UPDATE` on `workflow_runs`</li></ul> | `statement_timeout = 10s` |
| `sched_bench` | bench-cli (test only) | `COPY` into definitions for fast seeding; `SELECT` on history | test environments only |

All runtime roles also get `idle_in_transaction_session_timeout = 10s` and `lock_timeout = 2s` (set with `ALTER ROLE … SET`).

## 5. PostgreSQL configuration

| Setting | Local (compose, about 3 GB) | GCP reference (Cloud SQL Enterprise Plus, 32 vCPU) | Why |
|---|---|---|---|
| `shared_buffers` | 768MB | about 25% of RAM (managed default) | |
| `max_wal_size` / `checkpoint_timeout` | 4GB / 15min | 32GB / 15min | Fewer checkpoints and full-page writes |
| `wal_compression` | `lz4` | `lz4` | Less WAL volume |
| `synchronous_commit` | `on` | `on` | Durability (HLD RPO = 0). Batching amortises commit cost. |
| `autovacuum_max_workers` / `naptime` | 4 / 10s | 8 / 10s | Hot tables churn |
| `autovacuum_vacuum_cost_limit` | 4000 | 8000 | Keeps up with high-churn tables |
| `maintenance_work_mem` | 256MB | 2GB | |
| `random_page_cost` / `effective_io_concurrency` | 1.1 / 200 | 1.1 / 200 | SSD |
| `max_connections` | 200 | 800 | PgBouncer sits in front |
| `track_io_timing`, `pg_stat_statements` | on | on | Observability |
| `idle_in_transaction_session_timeout` | 10s (per role) | 10s | Stops long idle transactions blocking vacuum |

**PgBouncer** runs in transaction mode with `max_prepared_statements = 200` (PgBouncer 1.21 or later), so PgJDBC can keep using prepared statements. Two features need session state:
- `LISTEN` (the watcher wake-up) uses **one direct connection** that bypasses PgBouncer.
- Singleton tasks use **transaction-level** advisory locks (`pg_try_advisory_xact_lock`), which work in transaction mode.

## 6. Migrations and maintenance

- **Flyway** migrations live in `kairos-persistence/src/main/resources/db/migration`.
  - They run as a Kubernetes Job (a Helm pre-install and pre-upgrade hook), or as a one-shot Compose service, using `sched_migrator`.
  - Application roles never run DDL.
  - Changes follow **expand and contract**: add nullable column → deploy code → backfill → add constraint → remove the old column in a later release.
- **`PartitionManager`** (the `maintenance` role) runs daily as a CronJob and once at install:

| Task | Detail |
|---|---|
| Create partitions | Daily partitions for `run_history` and `task_outputs` for today plus 7 days ahead; monthly partitions for `audit_log`. Named `run_history_pYYYYMMDD`. |
| Drop old partitions | `run_history` and `task_outputs` partitions older than **retention (7 days) + maxAge (1 day) + 1 day of margin**: `ALTER TABLE … DETACH PARTITION … CONCURRENTLY`, then `DROP TABLE`. Never `DELETE`. `audit_log`: 90 days. |
| `ANALYZE` | Runs `ANALYZE` on the partitioned parents, because autovacuum skips them |
| Default partitions | Alerts if a `*_default` partition has any rows |

- **Purges** run as watcher singleton tasks, in batches of at most 10,000 rows per transaction:

| Table | Purge rule |
|---|---|
| `dead_letters` | Resolved more than **7 days** ago. OPEN and ACKNOWLEDGED entries are never purged. |
| `api_idempotency` | Older than 24h |
| `endpoint_health_minute`, `tenant_stats_minute` | Older than 7 days |
| `scheduler_nodes` | Heartbeat older than 1 day |
