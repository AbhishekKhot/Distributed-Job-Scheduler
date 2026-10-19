package dev.kairos.migrations.schema;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Creates the daily partitions of the day-partitioned tables for today (UTC) and the configured days ahead.
 *
 * <p>The DDL runs inside {@code scheduler.create_day_partition}, created by the migrations, which checks the table
 * against an allow-list and quotes every identifier and literal. Existing partitions are left alone, so running the
 * job again is a no-op.
 */
public final class PartitionCreator {

    private final Clock clock;
    private final int daysAhead;

    /**
     * Creates the partition creator.
     *
     * @param clock decides what today is
     * @param daysAhead how many days after today get a partition
     */
    public PartitionCreator(Clock clock, int daysAhead) {
        this.clock = clock;
        this.daysAhead = daysAhead;
    }

    /**
     * Ensures the partitions of every day-partitioned table of the target exist.
     *
     * @param target the database
     * @return the names of the partitions that now exist for the covered days
     */
    public List<String> createPartitions(MigrationTarget target) {
        JdbcClient jdbc = JdbcClient.create(target.dataSource());
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<String> partitions = new ArrayList<>();
        for (String table : target.kind().dayPartitionedTables()) {
            for (int offset = 0; offset <= daysAhead; offset++) {
                partitions.add(jdbc.sql("SELECT scheduler.create_day_partition(:table, :day)")
                        .param("table", table)
                        .param("day", today.plusDays(offset))
                        .query(String.class)
                        .single());
            }
        }
        return List.copyOf(partitions);
    }
}
