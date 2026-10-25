-- Durable cell-to-control auto-pause audit intent. The relay copies this id/time into audit_log
-- idempotently and only then removes the intent; no transaction spans the two databases.
CREATE TABLE scheduler.settlement_audits (
    audit_id uuid PRIMARY KEY,
    at timestamptz NOT NULL,
    team_id uuid NOT NULL,
    job_id uuid NOT NULL,
    consecutive_dead smallint NOT NULL CHECK (consecutive_dead >= 1)
);
CREATE INDEX settlement_audits_at_ix ON scheduler.settlement_audits(at, audit_id);
