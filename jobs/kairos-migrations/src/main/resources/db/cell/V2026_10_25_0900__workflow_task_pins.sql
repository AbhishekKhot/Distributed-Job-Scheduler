-- Retain endpoint versions after terminal task rows leave run_inflight. Workflow retries
-- must reuse the versions resolved when the occurrence was created, including skipped tasks.
ALTER TABLE scheduler.workflow_runs
    ADD COLUMN task_pins jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT workflow_runs_task_pins_ck CHECK (jsonb_typeof(task_pins) = 'object');
