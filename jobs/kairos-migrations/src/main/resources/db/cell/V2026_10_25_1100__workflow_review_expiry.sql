-- A review timeout remains final after the watcher clears blocked_until, even while independent branches finish.
ALTER TABLE scheduler.workflow_runs ADD COLUMN review_expired boolean NOT NULL DEFAULT false;
CREATE INDEX workflow_runs_progress_ix ON scheduler.workflow_runs(workflow_run_id) WHERE status IN (1, 2);
