package dev.kairos.maintenance.partitions.application;

/** Fixed allow-list: CLI arguments can never select arbitrary tables, columns or retention periods. */
public enum PartitionTable {
    RUN_HISTORY("run_history", "scheduled_at", 7),
    TASK_OUTPUTS("task_outputs", "scheduled_at", 7),
    AUDIT_LOG("audit_log", "at", 90);
    private final String table;
    private final String column;
    private final int retentionDays;

    PartitionTable(String table, String column, int retentionDays) {
        this.table = table;
        this.column = column;
        this.retentionDays = retentionDays;
    }

    public String table() {
        return table;
    }

    public String column() {
        return column;
    }

    public int retentionDays() {
        return retentionDays;
    }

    public String defaultName() {
        return table + "_default";
    }
}
