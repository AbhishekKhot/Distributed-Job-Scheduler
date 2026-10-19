-- Cell database schema (LLD section 2.3): definitions, schedules, runs and everything they
-- produce. References to control tables (team_id, endpoint_id) cross databases and are checked
-- by the application. Every object is schema-qualified so the script does not depend on the
-- session search_path.
--
-- Coded smallint columns use the codes of LLD section 2.3; the CHECK constraints list the
-- valid codes so a bad write fails at the database as well as in the domain enums.

CREATE TABLE scheduler.jobs (
    job_id      uuid        NOT NULL,
    team_id     uuid        NOT NULL,
    name        text        NOT NULL,
    version     integer     NOT NULL,
    status      text        NOT NULL DEFAULT 'ACTIVE',
    endpoint_id uuid        NOT NULL,
    spec        jsonb       NOT NULL,
    created_by  text        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT jobs_pk PRIMARY KEY (job_id),
    CONSTRAINT jobs_name_ck CHECK (char_length(name) BETWEEN 1 AND 128),
    CONSTRAINT jobs_version_ck CHECK (version >= 1),
    CONSTRAINT jobs_status_ck CHECK (
        status IN ('ACTIVE', 'PAUSED', 'AUTO_PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED', 'DELETED')),
    CONSTRAINT jobs_spec_ck CHECK (jsonb_typeof(spec) = 'object' AND octet_length(spec::text) <= 131072),
    CONSTRAINT jobs_created_by_ck CHECK (char_length(created_by) <= 64 AND created_by ~ '^(user|key):[A-Za-z0-9-]+$')
);

-- A deleted job frees its name.
CREATE UNIQUE INDEX jobs_team_name_uk ON scheduler.jobs (team_id, name) WHERE status <> 'DELETED';
-- List jobs (newest first) and the endpoint filter.
CREATE INDEX jobs_team_created_ix ON scheduler.jobs (team_id, created_at, job_id);
CREATE INDEX jobs_team_endpoint_ix ON scheduler.jobs (team_id, endpoint_id);
-- Purger: deleted definitions past retention.
CREATE INDEX jobs_deleted_ix ON scheduler.jobs (updated_at) WHERE status = 'DELETED';

CREATE TABLE scheduler.job_versions (
    job_id     uuid        NOT NULL,
    version    integer     NOT NULL,
    spec       jsonb       NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT job_versions_pk PRIMARY KEY (job_id, version),
    CONSTRAINT job_versions_job_fk FOREIGN KEY (job_id) REFERENCES scheduler.jobs (job_id) ON DELETE CASCADE,
    CONSTRAINT job_versions_version_ck CHECK (version >= 1),
    CONSTRAINT job_versions_spec_ck CHECK (jsonb_typeof(spec) = 'object' AND octet_length(spec::text) <= 131072)
);

CREATE TABLE scheduler.workflows (
    workflow_id uuid        NOT NULL,
    team_id     uuid        NOT NULL,
    name        text        NOT NULL,
    version     integer     NOT NULL,
    status      text        NOT NULL DEFAULT 'ACTIVE',
    spec        jsonb       NOT NULL,
    created_by  text        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    CONSTRAINT workflows_pk PRIMARY KEY (workflow_id),
    CONSTRAINT workflows_name_ck CHECK (char_length(name) BETWEEN 1 AND 128),
    CONSTRAINT workflows_version_ck CHECK (version >= 1),
    CONSTRAINT workflows_status_ck CHECK (status IN ('ACTIVE', 'PAUSED', 'CANCELLED', 'DELETED')),
    CONSTRAINT workflows_spec_ck CHECK (jsonb_typeof(spec) = 'object' AND octet_length(spec::text) <= 524288),
    CONSTRAINT workflows_created_by_ck
        CHECK (char_length(created_by) <= 64 AND created_by ~ '^(user|key):[A-Za-z0-9-]+$')
);

CREATE UNIQUE INDEX workflows_team_name_uk ON scheduler.workflows (team_id, name) WHERE status <> 'DELETED';
CREATE INDEX workflows_team_created_ix ON scheduler.workflows (team_id, created_at, workflow_id);
CREATE INDEX workflows_deleted_ix ON scheduler.workflows (updated_at) WHERE status = 'DELETED';

CREATE TABLE scheduler.workflow_versions (
    workflow_id uuid        NOT NULL,
    version     integer     NOT NULL,
    spec        jsonb       NOT NULL,
    created_at  timestamptz NOT NULL,
    CONSTRAINT workflow_versions_pk PRIMARY KEY (workflow_id, version),
    CONSTRAINT workflow_versions_workflow_fk
        FOREIGN KEY (workflow_id) REFERENCES scheduler.workflows (workflow_id) ON DELETE CASCADE,
    CONSTRAINT workflow_versions_version_ck CHECK (version >= 1),
    CONSTRAINT workflow_versions_spec_ck
        CHECK (jsonb_typeof(spec) = 'object' AND octet_length(spec::text) <= 524288)
);

CREATE TABLE scheduler.schedules (
    schedule_id        uuid        NOT NULL,
    target_type        smallint    NOT NULL,
    team_id            uuid        NOT NULL,
    endpoint_id        uuid,
    shard_id           smallint    NOT NULL,
    status             smallint    NOT NULL DEFAULT 1,
    next_fire_at       timestamptz,
    kind               smallint    NOT NULL,
    cron_expr          text,
    timezone           text        NOT NULL DEFAULT 'UTC',
    interval_ms        bigint,
    spread_ms          integer     NOT NULL DEFAULT 0,
    dst_policy         smallint    NOT NULL,
    misfire_policy     smallint    NOT NULL,
    max_catchup        smallint    NOT NULL DEFAULT 100,
    definition_version integer     NOT NULL,
    priority           smallint    NOT NULL DEFAULT 5,
    last_fired_local   timestamp,
    CONSTRAINT schedules_pk PRIMARY KEY (schedule_id),
    -- 1 job, 3 workflow.
    CONSTRAINT schedules_target_type_ck CHECK (target_type IN (1, 3)),
    CONSTRAINT schedules_endpoint_ck CHECK ((target_type = 1) = (endpoint_id IS NOT NULL)),
    CONSTRAINT schedules_shard_ck CHECK (shard_id BETWEEN 0 AND 255),
    -- 1 active, 2 paused, 3 held by a suspended endpoint.
    CONSTRAINT schedules_status_ck CHECK (status IN (1, 2, 3)),
    -- 1 one-time, 2 cron, 3 interval.
    CONSTRAINT schedules_kind_ck CHECK (kind IN (1, 2, 3)),
    CONSTRAINT schedules_cron_ck CHECK (
        (kind = 2) = (cron_expr IS NOT NULL) AND (cron_expr IS NULL OR char_length(cron_expr) <= 128)),
    CONSTRAINT schedules_interval_ck CHECK (
        (kind = 3) = (interval_ms IS NOT NULL) AND (interval_ms IS NULL OR interval_ms >= 1000)),
    CONSTRAINT schedules_timezone_ck CHECK (char_length(timezone) BETWEEN 1 AND 64),
    CONSTRAINT schedules_spread_ck CHECK (spread_ms >= 0),
    CONSTRAINT schedules_dst_policy_ck CHECK (dst_policy >= 1),
    -- 1 RUN_ALL, 2 RUN_LATEST, 3 SKIP.
    CONSTRAINT schedules_misfire_policy_ck CHECK (misfire_policy IN (1, 2, 3)),
    CONSTRAINT schedules_max_catchup_ck CHECK (max_catchup BETWEEN 0 AND 1000),
    CONSTRAINT schedules_definition_version_ck CHECK (definition_version >= 1),
    CONSTRAINT schedules_priority_ck CHECK (priority BETWEEN 1 AND 10)
);

-- Fire poller: due active schedules of a shard.
CREATE INDEX schedules_due_ix ON scheduler.schedules (shard_id, next_fire_at) WHERE status = 1;
-- Hold and release the schedules of a suspended or resumed endpoint.
CREATE INDEX schedules_endpoint_ix ON scheduler.schedules (endpoint_id) WHERE endpoint_id IS NOT NULL;

-- Every unfinished run. Hash-partitioned by shard (16 partitions); the shard is the leading key
-- of every hot index, so each poller cycle touches one partition.
CREATE TABLE scheduler.run_inflight (
    shard_id           smallint    NOT NULL,
    run_id             uuid        NOT NULL,
    team_id            uuid        NOT NULL,
    target_type        smallint    NOT NULL,
    definition_id      uuid        NOT NULL,
    definition_version integer     NOT NULL,
    target_id          uuid        NOT NULL,
    task_key           text        NOT NULL DEFAULT '',
    endpoint_id        uuid        NOT NULL,
    endpoint_version   integer     NOT NULL,
    scheduled_at       timestamptz NOT NULL,
    due_at             timestamptz NOT NULL,
    state              smallint    NOT NULL,
    attempt            smallint    NOT NULL DEFAULT 1,
    series_start       smallint    NOT NULL DEFAULT 1,
    series_started_at  timestamptz NOT NULL,
    infra_attempt      smallint    NOT NULL DEFAULT 0,
    deferrals          integer     NOT NULL DEFAULT 0,
    fence              integer     NOT NULL DEFAULT 0,
    owner_node         text,
    lease_until        timestamptz,
    remaining_deps     smallint    NOT NULL DEFAULT 0,
    cancel_requested   boolean     NOT NULL DEFAULT false,
    priority           smallint    NOT NULL,
    trigger            smallint    NOT NULL,
    max_lateness_ms    integer,
    CONSTRAINT run_inflight_pk PRIMARY KEY (shard_id, run_id),
    -- One run per occurrence. The partition key has to be part of a unique constraint on a
    -- partitioned table; target_id embeds the shard, so this is the same as the LLD constraint
    -- (target_id, task_key, scheduled_at).
    CONSTRAINT run_inflight_occurrence_uk UNIQUE (shard_id, target_id, task_key, scheduled_at),
    CONSTRAINT run_inflight_shard_ck CHECK (shard_id BETWEEN 0 AND 255),
    -- 1 job, 2 workflow task.
    CONSTRAINT run_inflight_target_type_ck CHECK (target_type IN (1, 2)),
    CONSTRAINT run_inflight_task_key_ck CHECK (
        char_length(task_key) <= 64 AND ((target_type = 1) = (task_key = ''))),
    -- 1 WAITING, 2 QUEUED, 3 RUNNING, 4 DELAYED.
    CONSTRAINT run_inflight_state_ck CHECK (state IN (1, 2, 3, 4)),
    CONSTRAINT run_inflight_attempt_ck CHECK (attempt >= 1 AND series_start BETWEEN 1 AND attempt),
    CONSTRAINT run_inflight_infra_attempt_ck CHECK (infra_attempt BETWEEN 0 AND 3),
    CONSTRAINT run_inflight_counters_ck CHECK (deferrals >= 0 AND fence >= 0 AND remaining_deps >= 0),
    CONSTRAINT run_inflight_owner_node_ck CHECK (owner_node IS NULL OR char_length(owner_node) <= 128),
    CONSTRAINT run_inflight_priority_ck CHECK (priority BETWEEN 1 AND 10),
    -- 1 schedule, 2 immediate, 3 manual, 4 backfill, 5 redrive.
    CONSTRAINT run_inflight_trigger_ck CHECK (trigger BETWEEN 1 AND 5),
    CONSTRAINT run_inflight_max_lateness_ck CHECK (max_lateness_ms IS NULL OR max_lateness_ms >= 0)
) PARTITION BY HASH (shard_id);

-- Delayed poller and stuck-queued sweeper: runs of a shard by state and due time.
CREATE INDEX run_inflight_due_ix ON scheduler.run_inflight (shard_id, state, due_at);
-- Lease sweeper: runs whose executor lease ran out.
CREATE INDEX run_inflight_lease_ix ON scheduler.run_inflight (owner_node, lease_until) WHERE owner_node IS NOT NULL;
-- List runs in an unfinished state (team, newest first).
CREATE INDEX run_inflight_team_scheduled_ix ON scheduler.run_inflight (team_id, scheduled_at);

-- Messages waiting to be published by the outbox relay. Hash-partitioned by shard.
CREATE TABLE scheduler.outbox (
    shard_id     smallint    NOT NULL,
    outbox_id    uuid        NOT NULL,
    topic        smallint    NOT NULL,
    msg_key      bytea       NOT NULL,
    payload      bytea       NOT NULL,
    trace_parent text,
    run_id       uuid,
    available_at timestamptz NOT NULL,
    created_at   timestamptz NOT NULL,
    CONSTRAINT outbox_pk PRIMARY KEY (shard_id, outbox_id),
    CONSTRAINT outbox_shard_ck CHECK (shard_id BETWEEN 0 AND 255),
    -- 1 run.high, 2 run.medium, 3 run.low, 4 run.cancel, 5 run.completed, 6 run.dead.
    CONSTRAINT outbox_topic_ck CHECK (topic BETWEEN 1 AND 6),
    CONSTRAINT outbox_msg_key_ck CHECK (octet_length(msg_key) BETWEEN 1 AND 256),
    CONSTRAINT outbox_payload_ck CHECK (octet_length(payload) <= 262144),
    -- W3C traceparent is 55 characters; tracestate is not stored.
    CONSTRAINT outbox_trace_parent_ck CHECK (trace_parent IS NULL OR char_length(trace_parent) <= 128),
    -- Dispatch and cancel rows always name their run.
    CONSTRAINT outbox_run_id_ck CHECK (topic > 4 OR run_id IS NOT NULL)
) PARTITION BY HASH (shard_id);

-- Outbox relay: due rows of a shard in order.
CREATE INDEX outbox_due_ix ON scheduler.outbox (shard_id, available_at);

-- One row per finished attempt; append-only; partitioned by day on scheduled_at.
CREATE TABLE scheduler.run_history (
    scheduled_at     timestamptz NOT NULL,
    run_id           uuid        NOT NULL,
    attempt          smallint    NOT NULL,
    infra_attempt    smallint    NOT NULL,
    team_id          uuid        NOT NULL,
    target_type      smallint    NOT NULL,
    definition_id    uuid        NOT NULL,
    target_id        uuid        NOT NULL,
    task_key         text        NOT NULL DEFAULT '',
    endpoint_id      uuid        NOT NULL,
    trigger          smallint    NOT NULL,
    outcome          smallint    NOT NULL,
    error_class      smallint,
    final            boolean     NOT NULL,
    http_status      smallint,
    started_at       timestamptz,
    finished_at      timestamptz NOT NULL,
    latency_ms       integer,
    executor_id      text,
    error            text,
    response_snippet text,
    CONSTRAINT run_history_pk PRIMARY KEY (scheduled_at, run_id, attempt, infra_attempt),
    CONSTRAINT run_history_attempt_ck CHECK (attempt >= 1 AND infra_attempt BETWEEN 0 AND 3),
    CONSTRAINT run_history_target_type_ck CHECK (target_type IN (1, 2)),
    CONSTRAINT run_history_task_key_ck CHECK (char_length(task_key) <= 64),
    CONSTRAINT run_history_trigger_ck CHECK (trigger BETWEEN 1 AND 5),
    -- 1 SUCCEEDED, 2 FAILED_RETRYING, 3 DEAD_LETTERED, 4 CANCELLED, 5 SKIPPED, 6 UPSTREAM_FAILED,
    -- 7 EXECUTOR_LOST.
    CONSTRAINT run_history_outcome_ck CHECK (outcome BETWEEN 1 AND 7),
    CONSTRAINT run_history_error_class_ck CHECK (error_class IS NULL OR error_class >= 1),
    CONSTRAINT run_history_http_status_ck CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    CONSTRAINT run_history_latency_ck CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT run_history_executor_id_ck CHECK (executor_id IS NULL OR char_length(executor_id) <= 128),
    CONSTRAINT run_history_error_ck CHECK (error IS NULL OR char_length(error) <= 512),
    CONSTRAINT run_history_response_snippet_ck CHECK (response_snippet IS NULL OR octet_length(response_snippet) <= 1024)
) PARTITION BY RANGE (scheduled_at);

-- List a job's runs and a workflow run's tasks (newest first).
CREATE INDEX run_history_definition_ix ON scheduler.run_history (definition_id, scheduled_at);
CREATE INDEX run_history_target_ix ON scheduler.run_history (target_id, scheduled_at);
-- List runs (team, newest first) and get one run.
CREATE INDEX run_history_team_scheduled_ix ON scheduler.run_history (team_id, scheduled_at);
CREATE INDEX run_history_run_ix ON scheduler.run_history (run_id);

CREATE TABLE scheduler.workflow_runs (
    workflow_run_id  uuid        NOT NULL,
    workflow_id      uuid        NOT NULL,
    workflow_version integer     NOT NULL,
    team_id          uuid        NOT NULL,
    shard_id         smallint    NOT NULL,
    scheduled_at     timestamptz NOT NULL,
    trigger          smallint    NOT NULL,
    status           smallint    NOT NULL DEFAULT 1,
    total            smallint    NOT NULL,
    succeeded        smallint    NOT NULL DEFAULT 0,
    failed           smallint    NOT NULL DEFAULT 0,
    cancelled        smallint    NOT NULL DEFAULT 0,
    skipped          smallint    NOT NULL DEFAULT 0,
    blocked_until    timestamptz,
    started_at       timestamptz,
    finished_at      timestamptz,
    CONSTRAINT workflow_runs_pk PRIMARY KEY (workflow_run_id),
    CONSTRAINT workflow_runs_occurrence_uk UNIQUE (workflow_id, workflow_version, scheduled_at),
    CONSTRAINT workflow_runs_version_ck CHECK (workflow_version >= 1),
    CONSTRAINT workflow_runs_shard_ck CHECK (shard_id BETWEEN 0 AND 255),
    CONSTRAINT workflow_runs_trigger_ck CHECK (trigger BETWEEN 1 AND 5),
    -- 1 RUNNING, 2 BLOCKED, 3 SUCCEEDED, 4 FAILED, 5 CANCELLED.
    CONSTRAINT workflow_runs_status_ck CHECK (status BETWEEN 1 AND 5),
    CONSTRAINT workflow_runs_blocked_ck CHECK (status <> 2 OR blocked_until IS NOT NULL),
    CONSTRAINT workflow_runs_counters_ck CHECK (
        total >= 1 AND succeeded >= 0 AND failed >= 0 AND cancelled >= 0 AND skipped >= 0
        AND succeeded + failed + cancelled + skipped <= total)
);

-- List a workflow's runs (newest first).
CREATE INDEX workflow_runs_workflow_scheduled_ix ON scheduler.workflow_runs (workflow_id, scheduled_at);
-- Review-timeout sweeper: blocked runs past their deadline.
CREATE INDEX workflow_runs_blocked_ix ON scheduler.workflow_runs (blocked_until) WHERE status = 2;

-- Partitioned by day on scheduled_at.
CREATE TABLE scheduler.task_outputs (
    scheduled_at    timestamptz NOT NULL,
    workflow_run_id uuid        NOT NULL,
    task_key        text        NOT NULL,
    team_id         uuid        NOT NULL,
    outputs         jsonb,
    sealed          bytea,
    created_at      timestamptz NOT NULL,
    CONSTRAINT task_outputs_pk PRIMARY KEY (scheduled_at, workflow_run_id, task_key),
    CONSTRAINT task_outputs_task_key_ck CHECK (char_length(task_key) BETWEEN 1 AND 64),
    CONSTRAINT task_outputs_outputs_ck CHECK (outputs IS NULL OR jsonb_typeof(outputs) = 'object'),
    CONSTRAINT task_outputs_size_ck CHECK (
        coalesce(octet_length(outputs::text), 0) + coalesce(octet_length(sealed), 0) <= 24576)
) PARTITION BY RANGE (scheduled_at);

CREATE TABLE scheduler.dead_letters (
    dlq_id             uuid        NOT NULL,
    team_id            uuid        NOT NULL,
    run_id             uuid        NOT NULL,
    shard_id           smallint    NOT NULL,
    scheduled_at       timestamptz NOT NULL,
    target_type        smallint    NOT NULL,
    definition_id      uuid        NOT NULL,
    definition_version integer     NOT NULL,
    target_id          uuid        NOT NULL,
    task_key           text        NOT NULL DEFAULT '',
    endpoint_id        uuid        NOT NULL,
    endpoint_version   integer     NOT NULL,
    attempt            smallint    NOT NULL,
    infra_attempt      smallint    NOT NULL,
    priority           smallint    NOT NULL,
    reason             smallint    NOT NULL,
    last_http_status   smallint,
    last_error         text,
    response_snippet   text,
    status             smallint    NOT NULL DEFAULT 1,
    assignee           text,
    notes              text,
    resolved_by        text,
    resolved_at        timestamptz,
    redrive_mode       smallint,
    created_at         timestamptz NOT NULL,
    CONSTRAINT dead_letters_pk PRIMARY KEY (dlq_id),
    -- A run is dead-lettered at most once.
    CONSTRAINT dead_letters_run_uk UNIQUE (run_id),
    CONSTRAINT dead_letters_shard_ck CHECK (shard_id BETWEEN 0 AND 255),
    CONSTRAINT dead_letters_target_type_ck CHECK (target_type IN (1, 2)),
    CONSTRAINT dead_letters_task_key_ck CHECK (char_length(task_key) <= 64),
    CONSTRAINT dead_letters_attempt_ck CHECK (attempt >= 1 AND infra_attempt BETWEEN 0 AND 3),
    CONSTRAINT dead_letters_priority_ck CHECK (priority BETWEEN 1 AND 10),
    CONSTRAINT dead_letters_reason_ck CHECK (reason >= 1),
    CONSTRAINT dead_letters_http_status_ck CHECK (last_http_status IS NULL OR last_http_status BETWEEN 100 AND 599),
    CONSTRAINT dead_letters_last_error_ck CHECK (last_error IS NULL OR char_length(last_error) <= 512),
    CONSTRAINT dead_letters_response_snippet_ck CHECK (response_snippet IS NULL OR octet_length(response_snippet) <= 1024),
    -- 1 OPEN, 2 ACKNOWLEDGED, 3 REDRIVEN, 4 DISCARDED.
    CONSTRAINT dead_letters_status_ck CHECK (status BETWEEN 1 AND 4),
    CONSTRAINT dead_letters_text_ck CHECK (
        (assignee IS NULL OR char_length(assignee) <= 128)
        AND (notes IS NULL OR char_length(notes) <= 2048)
        AND (resolved_by IS NULL OR char_length(resolved_by) <= 128)),
    CONSTRAINT dead_letters_resolved_ck CHECK ((status IN (3, 4)) = (resolved_at IS NOT NULL)),
    -- 1 original configuration, 2 current.
    CONSTRAINT dead_letters_redrive_mode_ck CHECK (redrive_mode IS NULL OR redrive_mode IN (1, 2))
);

-- List DLQ entries by status (newest first).
CREATE INDEX dead_letters_team_status_created_ix ON scheduler.dead_letters (team_id, status, created_at);
-- Purger: resolved entries past retention. Unresolved entries are never purged.
CREATE INDEX dead_letters_resolved_ix ON scheduler.dead_letters (resolved_at) WHERE status IN (3, 4);

CREATE TABLE scheduler.job_health (
    job_id           uuid        NOT NULL,
    team_id          uuid        NOT NULL,
    recent_mask      smallint    NOT NULL DEFAULT 0,
    recent_count     smallint    NOT NULL DEFAULT 0,
    consecutive_dead smallint    NOT NULL DEFAULT 0,
    unhealthy_since  timestamptz,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT job_health_pk PRIMARY KEY (job_id),
    -- The last 10 outcomes as bits.
    CONSTRAINT job_health_recent_ck CHECK (recent_mask BETWEEN 0 AND 1023 AND recent_count BETWEEN 0 AND 10),
    CONSTRAINT job_health_consecutive_dead_ck CHECK (consecutive_dead >= 0)
);

CREATE TABLE scheduler.endpoint_health_minute (
    endpoint_id    uuid        NOT NULL,
    minute         timestamptz NOT NULL,
    node_id        text        NOT NULL,
    team_id        uuid        NOT NULL,
    attempts       integer     NOT NULL DEFAULT 0,
    successes      integer     NOT NULL DEFAULT 0,
    retryable      integer     NOT NULL DEFAULT 0,
    non_retryable  integer     NOT NULL DEFAULT 0,
    dead_lettered  integer     NOT NULL DEFAULT 0,
    latency_sum_ms bigint      NOT NULL DEFAULT 0,
    latency_max_ms integer     NOT NULL DEFAULT 0,
    CONSTRAINT endpoint_health_minute_pk PRIMARY KEY (endpoint_id, minute, node_id),
    CONSTRAINT endpoint_health_minute_minute_ck CHECK (minute = date_trunc('minute', minute)),
    CONSTRAINT endpoint_health_minute_counters_ck CHECK (
        attempts >= 0 AND successes >= 0 AND retryable >= 0 AND non_retryable >= 0 AND dead_lettered >= 0
        AND latency_sum_ms >= 0 AND latency_max_ms >= 0)
);

-- Purger: minutes past retention.
CREATE INDEX endpoint_health_minute_minute_ix ON scheduler.endpoint_health_minute (minute);

CREATE TABLE scheduler.team_stats_minute (
    team_id        uuid        NOT NULL,
    minute         timestamptz NOT NULL,
    node_id        text        NOT NULL,
    attempts       integer     NOT NULL DEFAULT 0,
    successes      integer     NOT NULL DEFAULT 0,
    dead_lettered  integer     NOT NULL DEFAULT 0,
    latency_sum_ms bigint      NOT NULL DEFAULT 0,
    CONSTRAINT team_stats_minute_pk PRIMARY KEY (team_id, minute, node_id),
    CONSTRAINT team_stats_minute_minute_ck CHECK (minute = date_trunc('minute', minute)),
    CONSTRAINT team_stats_minute_counters_ck CHECK (
        attempts >= 0 AND successes >= 0 AND dead_lettered >= 0 AND latency_sum_ms >= 0)
);

CREATE INDEX team_stats_minute_minute_ix ON scheduler.team_stats_minute (minute);

CREATE TABLE scheduler.shard_leases (
    shard_id    smallint    NOT NULL,
    owner_node  text,
    generation  bigint      NOT NULL DEFAULT 0,
    lease_until timestamptz,
    CONSTRAINT shard_leases_pk PRIMARY KEY (shard_id),
    CONSTRAINT shard_leases_shard_ck CHECK (shard_id BETWEEN 0 AND 255),
    CONSTRAINT shard_leases_owner_node_ck CHECK (owner_node IS NULL OR char_length(owner_node) <= 128),
    CONSTRAINT shard_leases_generation_ck CHECK (generation >= 0)
);

-- Idempotent creates of resources stored in this cell (LLD section 2.2, api_idempotency).
CREATE TABLE scheduler.api_idempotency (
    team_id       uuid        NOT NULL,
    idem_key      text        NOT NULL,
    request_hash  bytea       NOT NULL,
    status        smallint    NOT NULL,
    http_status   smallint,
    response_body jsonb,
    created_at    timestamptz NOT NULL,
    CONSTRAINT api_idempotency_pk PRIMARY KEY (team_id, idem_key),
    CONSTRAINT api_idempotency_idem_key_ck CHECK (char_length(idem_key) BETWEEN 1 AND 64),
    CONSTRAINT api_idempotency_status_ck CHECK (status IN (1, 2)),
    CONSTRAINT api_idempotency_http_status_ck CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599)
);

CREATE INDEX api_idempotency_created_ix ON scheduler.api_idempotency (created_at);
