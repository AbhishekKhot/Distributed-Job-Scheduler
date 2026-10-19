-- Partitions and autovacuum settings of the cell tables.
--
-- run_inflight and outbox: 16 hash partitions on shard_id, created once.
-- run_history and task_outputs: daily range partitions on scheduled_at. The migrations job
-- creates today plus the configured days ahead; the partition manager (kairos-maintenance)
-- keeps creating and dropping them afterwards.

DO $$
BEGIN
    FOR remainder IN 0..15 LOOP
        EXECUTE format(
            'CREATE TABLE scheduler.%I PARTITION OF scheduler.run_inflight FOR VALUES WITH (MODULUS 16, REMAINDER %s)',
            'run_inflight_h' || lpad(remainder::text, 2, '0'), remainder);
        EXECUTE format(
            'CREATE TABLE scheduler.%I PARTITION OF scheduler.outbox FOR VALUES WITH (MODULUS 16, REMAINDER %s)',
            'outbox_h' || lpad(remainder::text, 2, '0'), remainder);
    END LOOP;
END;
$$;

-- Rows outside every daily partition (for example a backfill of an old occurrence) land here
-- instead of failing the insert. The partition manager moves them out before it creates a
-- partition that overlaps them.
CREATE TABLE scheduler.run_history_default PARTITION OF scheduler.run_history DEFAULT;
CREATE TABLE scheduler.task_outputs_default PARTITION OF scheduler.task_outputs DEFAULT;

-- Creates the partition of one day if it does not exist yet. The parent is checked against an
-- allow-list and identifiers and literals go through format() with %I and %L, so no caller value
-- reaches the DDL unquoted.
CREATE FUNCTION scheduler.create_day_partition(parent_table text, day date)
    RETURNS text
    LANGUAGE plpgsql
    SET search_path = pg_catalog
AS $$
DECLARE
    partition_name text;
BEGIN
    IF parent_table IS NULL OR parent_table NOT IN ('run_history', 'task_outputs') THEN
        RAISE EXCEPTION 'not a day-partitioned table: %', parent_table USING ERRCODE = '22023';
    END IF;
    IF day IS NULL THEN
        RAISE EXCEPTION 'day is required' USING ERRCODE = '22004';
    END IF;
    partition_name := parent_table || '_p' || to_char(day, 'YYYYMMDD');
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS scheduler.%I PARTITION OF scheduler.%I FOR VALUES FROM (%L) TO (%L)',
        partition_name,
        parent_table,
        day::timestamp AT TIME ZONE 'UTC',
        (day + 1)::timestamp AT TIME ZONE 'UTC');
    RETURN partition_name;
END;
$$;

-- Only the schema owner creates partitions; the default privileges would otherwise let the
-- application role run DDL through this function.
REVOKE EXECUTE ON FUNCTION scheduler.create_day_partition(text, date) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION scheduler.create_day_partition(text, date) FROM "${appRole}";

-- Hot tables see a steady stream of inserts, updates and deletes (outbox rows live for
-- milliseconds). Vacuum them after a small fraction of dead rows and let each run do more work,
-- so dead tuples never pile up in the indexes the pollers scan. Storage parameters cannot be set
-- on a partitioned table, so they go on every hash partition.
DO $$
BEGIN
    FOR remainder IN 0..15 LOOP
        EXECUTE format(
            'ALTER TABLE scheduler.%I SET (autovacuum_vacuum_scale_factor = 0.01, '
            'autovacuum_vacuum_insert_scale_factor = 0.02, autovacuum_analyze_scale_factor = 0.02, '
            'autovacuum_vacuum_cost_limit = 2000, fillfactor = 80)',
            'run_inflight_h' || lpad(remainder::text, 2, '0'));
        EXECUTE format(
            'ALTER TABLE scheduler.%I SET (autovacuum_vacuum_scale_factor = 0.01, '
            'autovacuum_vacuum_insert_scale_factor = 0.02, autovacuum_analyze_scale_factor = 0.02, '
            'autovacuum_vacuum_cost_limit = 2000)',
            'outbox_h' || lpad(remainder::text, 2, '0'));
    END LOOP;
END;
$$;

-- next_fire_at changes on every fire, so the table turns over as fast as the runs it creates.
ALTER TABLE scheduler.schedules SET (
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_analyze_scale_factor = 0.02,
    autovacuum_vacuum_cost_limit = 2000,
    fillfactor = 80);
