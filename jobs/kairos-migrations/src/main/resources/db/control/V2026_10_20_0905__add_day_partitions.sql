-- Daily range partitions for audit_log. Partitions are named <parent>_pYYYYMMDD and cover one UTC
-- day. The migrations job creates today plus the configured days ahead; the partition manager
-- (kairos-maintenance) keeps creating and dropping them afterwards.

-- Rows outside every daily partition land here instead of failing the insert. The partition
-- manager moves them out before it creates a partition that overlaps them.
CREATE TABLE scheduler.audit_log_default PARTITION OF scheduler.audit_log DEFAULT;

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
    IF parent_table IS NULL OR parent_table NOT IN ('audit_log') THEN
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
