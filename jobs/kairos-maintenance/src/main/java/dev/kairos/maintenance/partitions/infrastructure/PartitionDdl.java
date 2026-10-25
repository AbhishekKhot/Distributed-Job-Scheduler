package dev.kairos.maintenance.partitions.infrastructure;

import dev.kairos.maintenance.partitions.application.PartitionDay;
import dev.kairos.maintenance.partitions.application.PartitionTable;

/** All interpolated SQL identifiers and bounds come only from fixed enums and validated calendar days. */
public final class PartitionDdl {
    private PartitionDdl() {}

    public static String qualified(String identifier) {
        if (!identifier.matches("^(run_history|task_outputs|audit_log)(_default|_p[0-9]{8})?$"))
            throw new IllegalArgumentException("Invalid partition identifier");
        return "scheduler.\"" + identifier + "\"";
    }

    public static String lockParent(PartitionTable table) {
        return "LOCK TABLE ONLY " + qualified(table.table()) + " IN ACCESS EXCLUSIVE MODE";
    }

    public static String lockDefault(PartitionTable table) {
        return "LOCK TABLE " + qualified(table.defaultName()) + " IN ACCESS EXCLUSIVE MODE";
    }

    public static String create(PartitionDay day) {
        return "CREATE TABLE " + qualified(day.name()) + " (LIKE "
                + qualified(day.table().table()) + " INCLUDING ALL)";
    }

    public static String move(PartitionDay day) {
        return "WITH moved AS (DELETE FROM " + qualified(day.table().defaultName()) + " WHERE "
                + day.table().column() + ">=:start AND " + day.table().column() + "<:end RETURNING *) INSERT INTO "
                + qualified(day.name()) + " SELECT * FROM moved";
    }

    public static String boundCheck(PartitionDay day) {
        return "ALTER TABLE " + qualified(day.name()) + " ADD CHECK ("
                + day.table().column() + ">=TIMESTAMPTZ '" + day.date() + " 00:00:00+00' AND "
                + day.table().column() + "<TIMESTAMPTZ '" + day.date().plusDays(1) + " 00:00:00+00')";
    }

    public static String attach(PartitionDay day) {
        return "ALTER TABLE " + qualified(day.table().table()) + " ATTACH PARTITION " + qualified(day.name())
                + " FOR VALUES FROM ('" + day.date() + " 00:00:00+00') TO ('"
                + day.date().plusDays(1) + " 00:00:00+00')";
    }

    public static String drop(PartitionDay day) {
        return "DROP TABLE " + qualified(day.name());
    }

    public static String purgeDefault(PartitionTable table) {
        return "DELETE FROM " + qualified(table.defaultName()) + " WHERE ctid IN (SELECT ctid FROM ONLY "
                + qualified(table.defaultName()) + " WHERE " + table.column()
                + "<:cutoff LIMIT :batch FOR UPDATE SKIP LOCKED)";
    }
}
