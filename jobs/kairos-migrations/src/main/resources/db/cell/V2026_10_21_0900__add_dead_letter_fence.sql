-- Fence of a run when it was dead-lettered (LLD section 2.3, dead_letters). A redrive recreates
-- the run from this fence, so dispatch, result and cancel messages of the dead-lettered run that
-- are still in flight can never claim or settle the redriven one.
--
-- The column is added with a constant default, which Postgres stores in the catalogue without
-- rewriting the table, so existing rows read 0 (the initial fence) and the NOT NULL holds at once.
-- The default is then dropped: the settler copies the fence from run_inflight on every insert,
-- and a missing value must fail rather than silently restart a redriven run at 0.

ALTER TABLE scheduler.dead_letters
    ADD COLUMN fence integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT dead_letters_fence_ck CHECK (fence >= 0);

ALTER TABLE scheduler.dead_letters ALTER COLUMN fence DROP DEFAULT;
