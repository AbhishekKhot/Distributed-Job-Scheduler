-- Control database schema (LLD section 2.2): teams, users, credentials, endpoints and the
-- platform tables shared by every cell. Every object is schema-qualified so the script does not
-- depend on the session search_path.

CREATE TABLE scheduler.teams (
    team_id       uuid        NOT NULL,
    slug          text        NOT NULL,
    quotas        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    retry_default jsonb,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT teams_pk PRIMARY KEY (team_id),
    CONSTRAINT teams_slug_uk UNIQUE (slug),
    -- Length is checked before the pattern so an oversized value never reaches the regex.
    CONSTRAINT teams_slug_ck CHECK (char_length(slug) BETWEEN 3 AND 63 AND slug ~ '^[a-z][a-z0-9-]{2,62}$'),
    CONSTRAINT teams_quotas_ck CHECK (jsonb_typeof(quotas) = 'object'),
    CONSTRAINT teams_retry_default_ck CHECK (retry_default IS NULL OR jsonb_typeof(retry_default) = 'object')
);

CREATE TABLE scheduler.users (
    user_id             uuid        NOT NULL,
    team_id             uuid,
    email               text        NOT NULL,
    password_hash       text        NOT NULL,
    status              text        NOT NULL DEFAULT 'ACTIVE',
    failed_logins       smallint    NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    password_changed_at timestamptz NOT NULL,
    last_login_at       timestamptz,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    CONSTRAINT users_pk PRIMARY KEY (user_id),
    CONSTRAINT users_team_fk FOREIGN KEY (team_id) REFERENCES scheduler.teams (team_id),
    CONSTRAINT users_email_uk UNIQUE (email),
    CONSTRAINT users_email_ck CHECK (char_length(email) BETWEEN 3 AND 254 AND email = lower(email)),
    CONSTRAINT users_password_hash_ck CHECK (password_hash LIKE '$argon2id$%'),
    CONSTRAINT users_status_ck CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT users_failed_logins_ck CHECK (failed_logins >= 0)
);

-- List members (newest first, cursor on created_at).
CREATE INDEX users_team_created_ix ON scheduler.users (team_id, created_at, user_id) WHERE team_id IS NOT NULL;

CREATE TABLE scheduler.user_sessions (
    session_id   uuid        NOT NULL,
    user_id      uuid        NOT NULL,
    family_id    uuid        NOT NULL,
    token_hash   bytea       NOT NULL,
    status       text        NOT NULL DEFAULT 'ACTIVE',
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    last_used_at timestamptz,
    user_agent   text,
    ip_address   inet,
    CONSTRAINT user_sessions_pk PRIMARY KEY (session_id),
    CONSTRAINT user_sessions_user_fk FOREIGN KEY (user_id) REFERENCES scheduler.users (user_id) ON DELETE CASCADE,
    CONSTRAINT user_sessions_token_hash_uk UNIQUE (token_hash),
    -- SHA-256 digest.
    CONSTRAINT user_sessions_token_hash_ck CHECK (octet_length(token_hash) = 32),
    CONSTRAINT user_sessions_status_ck CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    CONSTRAINT user_sessions_user_agent_ck CHECK (user_agent IS NULL OR char_length(user_agent) <= 256),
    CONSTRAINT user_sessions_expiry_ck CHECK (expires_at > created_at)
);

CREATE INDEX user_sessions_user_ix ON scheduler.user_sessions (user_id);
-- Reuse detection revokes a whole family.
CREATE INDEX user_sessions_family_ix ON scheduler.user_sessions (family_id);
-- Purger: expired sessions.
CREATE INDEX user_sessions_expires_ix ON scheduler.user_sessions (expires_at);

CREATE TABLE scheduler.api_keys (
    key_id         text        NOT NULL,
    team_id        uuid        NOT NULL,
    name           text        NOT NULL,
    secret_hash    bytea       NOT NULL,
    pepper_version smallint    NOT NULL,
    created_by     uuid        NOT NULL,
    created_at     timestamptz NOT NULL,
    expires_at     timestamptz,
    revoked_at     timestamptz,
    last_used_at   timestamptz,
    CONSTRAINT api_keys_pk PRIMARY KEY (key_id),
    CONSTRAINT api_keys_team_fk FOREIGN KEY (team_id) REFERENCES scheduler.teams (team_id),
    CONSTRAINT api_keys_created_by_fk FOREIGN KEY (created_by) REFERENCES scheduler.users (user_id),
    CONSTRAINT api_keys_key_id_ck CHECK (char_length(key_id) = 16 AND key_id ~ '^[A-Za-z0-9]{16}$'),
    CONSTRAINT api_keys_name_ck CHECK (char_length(name) BETWEEN 1 AND 64),
    -- HMAC-SHA256 digest.
    CONSTRAINT api_keys_secret_hash_ck CHECK (octet_length(secret_hash) = 32),
    CONSTRAINT api_keys_pepper_version_ck CHECK (pepper_version >= 1)
);

-- List API keys (newest first).
CREATE INDEX api_keys_team_created_ix ON scheduler.api_keys (team_id, created_at, key_id);

CREATE TABLE scheduler.team_keysets (
    team_id          uuid        NOT NULL,
    purpose          text        NOT NULL,
    encrypted_keyset bytea       NOT NULL,
    kek_uri          text        NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT team_keysets_pk PRIMARY KEY (team_id, purpose),
    CONSTRAINT team_keysets_team_fk FOREIGN KEY (team_id) REFERENCES scheduler.teams (team_id),
    CONSTRAINT team_keysets_purpose_ck CHECK (purpose IN ('SECRETS', 'OUTPUTS', 'SIGNING'))
);

CREATE TABLE scheduler.team_secrets (
    team_id     uuid        NOT NULL,
    name        text        NOT NULL,
    version     integer     NOT NULL,
    kind        text        NOT NULL,
    ciphertext  bytea       NOT NULL,
    created_at  timestamptz NOT NULL,
    disabled_at timestamptz,
    CONSTRAINT team_secrets_pk PRIMARY KEY (team_id, name, version),
    CONSTRAINT team_secrets_team_fk FOREIGN KEY (team_id) REFERENCES scheduler.teams (team_id),
    CONSTRAINT team_secrets_name_ck CHECK (char_length(name) BETWEEN 1 AND 128),
    CONSTRAINT team_secrets_version_ck CHECK (version >= 1),
    CONSTRAINT team_secrets_kind_ck CHECK (kind IN ('BASIC', 'BEARER', 'API_KEY', 'OAUTH2_CLIENT')),
    CONSTRAINT team_secrets_ciphertext_ck CHECK (octet_length(ciphertext) <= 16384)
);

CREATE TABLE scheduler.team_ca_bundles (
    team_id    uuid        NOT NULL,
    name       text        NOT NULL,
    pem        text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT team_ca_bundles_pk PRIMARY KEY (team_id, name),
    CONSTRAINT team_ca_bundles_team_fk FOREIGN KEY (team_id) REFERENCES scheduler.teams (team_id),
    CONSTRAINT team_ca_bundles_name_ck CHECK (char_length(name) BETWEEN 1 AND 128),
    CONSTRAINT team_ca_bundles_pem_ck CHECK (octet_length(pem) <= 65536)
);

CREATE TABLE scheduler.api_endpoints (
    endpoint_id       uuid        NOT NULL,
    team_id           uuid        NOT NULL,
    name              text        NOT NULL,
    version           integer     NOT NULL,
    status            text        NOT NULL DEFAULT 'ACTIVE',
    status_reason     text,
    status_changed_at timestamptz,
    spec              jsonb       NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT api_endpoints_pk PRIMARY KEY (endpoint_id),
    CONSTRAINT api_endpoints_team_fk FOREIGN KEY (team_id) REFERENCES scheduler.teams (team_id),
    CONSTRAINT api_endpoints_team_name_uk UNIQUE (team_id, name),
    CONSTRAINT api_endpoints_name_ck CHECK (char_length(name) BETWEEN 2 AND 63 AND name ~ '^[a-z][a-z0-9-]{1,62}$'),
    CONSTRAINT api_endpoints_version_ck CHECK (version >= 1),
    CONSTRAINT api_endpoints_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED')),
    CONSTRAINT api_endpoints_status_reason_ck CHECK (status_reason IS NULL OR char_length(status_reason) <= 64),
    CONSTRAINT api_endpoints_spec_ck CHECK (jsonb_typeof(spec) = 'object' AND octet_length(spec::text) <= 65536)
);

-- List endpoints (newest first).
CREATE INDEX api_endpoints_team_created_ix ON scheduler.api_endpoints (team_id, created_at, endpoint_id);

CREATE TABLE scheduler.api_endpoint_versions (
    endpoint_id uuid        NOT NULL,
    version     integer     NOT NULL,
    spec        jsonb       NOT NULL,
    created_at  timestamptz NOT NULL,
    created_by  text        NOT NULL,
    CONSTRAINT api_endpoint_versions_pk PRIMARY KEY (endpoint_id, version),
    CONSTRAINT api_endpoint_versions_endpoint_fk
        FOREIGN KEY (endpoint_id) REFERENCES scheduler.api_endpoints (endpoint_id),
    CONSTRAINT api_endpoint_versions_version_ck CHECK (version >= 1),
    CONSTRAINT api_endpoint_versions_spec_ck
        CHECK (jsonb_typeof(spec) = 'object' AND octet_length(spec::text) <= 65536),
    CONSTRAINT api_endpoint_versions_created_by_ck CHECK (char_length(created_by) BETWEEN 1 AND 128)
);

CREATE TABLE scheduler.platform_keysets (
    purpose          text        NOT NULL,
    encrypted_keyset bytea       NOT NULL,
    kek_uri          text        NOT NULL,
    rotated_at       timestamptz NOT NULL,
    CONSTRAINT platform_keysets_pk PRIMARY KEY (purpose),
    CONSTRAINT platform_keysets_purpose_ck CHECK (purpose IN ('JWT_SIGNING'))
);

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
    -- 1 in progress, 2 done.
    CONSTRAINT api_idempotency_status_ck CHECK (status IN (1, 2)),
    CONSTRAINT api_idempotency_http_status_ck CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599)
);

-- Purger: records older than 24h.
CREATE INDEX api_idempotency_created_ix ON scheduler.api_idempotency (created_at);

-- Partitioned by day on "at"; the partition key must be part of the primary key.
CREATE TABLE scheduler.audit_log (
    audit_id      uuid        NOT NULL,
    at            timestamptz NOT NULL,
    team_id       uuid,
    actor         text        NOT NULL,
    action        text        NOT NULL,
    resource_type text,
    resource_id   text,
    details       jsonb,
    CONSTRAINT audit_log_pk PRIMARY KEY (at, audit_id),
    CONSTRAINT audit_log_actor_ck CHECK (
        char_length(actor) <= 160 AND actor ~ '^(user|key|operator|system):[A-Za-z0-9_.:-]+$'),
    CONSTRAINT audit_log_action_ck CHECK (char_length(action) BETWEEN 1 AND 64),
    CONSTRAINT audit_log_resource_type_ck CHECK (resource_type IS NULL OR char_length(resource_type) <= 64),
    CONSTRAINT audit_log_resource_id_ck CHECK (resource_id IS NULL OR char_length(resource_id) <= 128)
) PARTITION BY RANGE (at);

-- Read the audit log: team and time range, newest first.
CREATE INDEX audit_log_team_at_ix ON scheduler.audit_log (team_id, at);

CREATE TABLE scheduler.cell_map (
    cell_id        smallint NOT NULL,
    shard_from     smallint NOT NULL,
    shard_to       smallint NOT NULL,
    datasource_ref text     NOT NULL,
    status         text     NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT cell_map_pk PRIMARY KEY (cell_id),
    CONSTRAINT cell_map_cell_id_ck CHECK (cell_id >= 0),
    CONSTRAINT cell_map_shards_ck CHECK (shard_from BETWEEN 0 AND 255 AND shard_to BETWEEN shard_from AND 255),
    CONSTRAINT cell_map_datasource_ref_ck CHECK (char_length(datasource_ref) BETWEEN 1 AND 64),
    CONSTRAINT cell_map_status_ck CHECK (status IN ('ACTIVE', 'DRAINING', 'READ_ONLY'))
);

CREATE TABLE scheduler.scheduler_nodes (
    node_id      text        NOT NULL,
    roles        text        NOT NULL,
    version      text        NOT NULL,
    started_at   timestamptz NOT NULL,
    heartbeat_at timestamptz NOT NULL,
    CONSTRAINT scheduler_nodes_pk PRIMARY KEY (node_id),
    CONSTRAINT scheduler_nodes_node_id_ck CHECK (char_length(node_id) BETWEEN 1 AND 128),
    CONSTRAINT scheduler_nodes_roles_ck CHECK (char_length(roles) BETWEEN 1 AND 128),
    CONSTRAINT scheduler_nodes_version_ck CHECK (char_length(version) BETWEEN 1 AND 64)
);

CREATE TABLE scheduler.scheduler_flags (
    name       text        NOT NULL,
    value_ts   timestamptz,
    updated_at timestamptz NOT NULL,
    CONSTRAINT scheduler_flags_pk PRIMARY KEY (name),
    CONSTRAINT scheduler_flags_name_ck CHECK (char_length(name) BETWEEN 1 AND 64)
);
