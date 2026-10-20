package dev.kairos.migrations.schema;

import java.util.List;

/** The two kinds of Kairos database, each with its own Flyway location and day-partitioned tables. */
public enum DatabaseKind {

    /** The control database: teams, users, credentials, endpoints. */
    CONTROL("classpath:db/control", List.of("audit_log")),

    /** A cell database: definitions, schedules, runs. */
    CELL("classpath:db/cell", List.of("run_history", "task_outputs"));

    private final String location;
    private final List<String> dayPartitionedTables;

    DatabaseKind(String location, List<String> dayPartitionedTables) {
        this.location = location;
        this.dayPartitionedTables = dayPartitionedTables;
    }

    /**
     * Returns the Flyway location of this kind's migrations.
     *
     * @return a classpath location
     */
    public String location() {
        return location;
    }

    /**
     * Returns the tables range-partitioned by day.
     *
     * @return table names in schema {@code scheduler}
     */
    public List<String> dayPartitionedTables() {
        return dayPartitionedTables;
    }
}
