package dev.kairos.migrations.schema;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Seeds {@code cell_map} in the control database from the configured cells.
 *
 * <p>The 256 shards are split into equal, contiguous ranges, one per cell in cell order: with two cells, cell 0 owns
 * shards 0–127 and cell 1 owns 128–255. Missing rows are inserted. Existing rows are never changed: moving shards
 * between cells is an operator action, so a row that disagrees with the configuration, or a row for a cell that is no
 * longer configured, fails the job instead.
 *
 * <p>Two copies of the job that check and insert at the same time insert identical rows, and the insert ignores a row
 * that already exists, so the race is harmless.
 */
public final class CellMapSeeder {

    /** The number of shards; fixed forever (LLD section 2). */
    public static final int SHARD_COUNT = 256;

    private static final String INSERT_CELL = """
            INSERT INTO scheduler.cell_map (cell_id, shard_from, shard_to, datasource_ref, status)
            VALUES (:cellId, :shardFrom, :shardTo, :datasourceRef, 'ACTIVE')
            ON CONFLICT (cell_id) DO NOTHING
            """;

    private static final String SELECT_CELLS = """
            SELECT cell_id, shard_from, shard_to, datasource_ref
              FROM scheduler.cell_map
             ORDER BY cell_id
            """;

    /**
     * One row of {@code cell_map}.
     *
     * @param cellId the cell number
     * @param shardFrom the first shard of the cell
     * @param shardTo the last shard of the cell, inclusive
     * @param datasourceRef the name services use to look up the cell's connection settings
     */
    public record CellRange(int cellId, int shardFrom, int shardTo, String datasourceRef) {}

    /**
     * Returns the layout for a number of cells.
     *
     * @param cellCount the number of configured cells
     * @return one range per cell, in cell order
     * @throws IllegalArgumentException if the shards cannot be split evenly
     */
    public static List<CellRange> layout(int cellCount) {
        if (cellCount < 1 || SHARD_COUNT % cellCount != 0) {
            throw new IllegalArgumentException(
                    "The number of cells must divide " + SHARD_COUNT + " evenly, got " + cellCount);
        }
        int shardsPerCell = SHARD_COUNT / cellCount;
        List<CellRange> ranges = new ArrayList<>(cellCount);
        for (int cell = 0; cell < cellCount; cell++) {
            ranges.add(new CellRange(cell, cell * shardsPerCell, (cell + 1) * shardsPerCell - 1, "cell-" + cell));
        }
        return List.copyOf(ranges);
    }

    /**
     * Checks the existing rows, then inserts the missing ones.
     *
     * <p>The check runs before any write, so a configuration that disagrees with {@code cell_map} changes nothing.
     *
     * @param control the control database
     * @param cellCount the number of configured cells
     * @throws IllegalStateException if {@code cell_map} disagrees with the configuration
     */
    public void seed(MigrationTarget control, int cellCount) {
        List<CellRange> expected = layout(cellCount);
        JdbcClient jdbc = JdbcClient.create(control.dataSource());
        for (CellRange existing : existing(jdbc)) {
            if (existing.cellId() >= cellCount || !existing.equals(expected.get(existing.cellId()))) {
                throw new IllegalStateException("cell_map row " + existing + " does not match the " + cellCount
                        + " configured cells; change the cell layout with an operator command, not by configuration");
            }
        }
        for (CellRange range : expected) {
            // Another copy of the job may insert the same row concurrently; both write the same values.
            jdbc.sql(INSERT_CELL)
                    .param("cellId", (short) range.cellId())
                    .param("shardFrom", (short) range.shardFrom())
                    .param("shardTo", (short) range.shardTo())
                    .param("datasourceRef", range.datasourceRef())
                    .update();
        }
    }

    private static List<CellRange> existing(JdbcClient jdbc) {
        return jdbc.sql(SELECT_CELLS)
                .query((row, rowNumber) -> new CellRange(
                        row.getInt("cell_id"),
                        row.getInt("shard_from"),
                        row.getInt("shard_to"),
                        row.getString("datasource_ref")))
                .list();
    }
}
