package dev.kairos.persistence.cell;

import dev.kairos.persistence.jdbc.Database;
import java.util.List;
import java.util.Objects;

/**
 * Where the router reads the shard-to-cell layout from.
 *
 * <p>Production reads {@code cell_map} in the control database ({@link #fromControl(Database)}); tests can supply rows
 * directly.
 */
@FunctionalInterface
public interface CellMapSource {

    /** Reads every row of {@code cell_map}; the table holds one row per cell, so it is never large. */
    String SELECT_CELL_MAP = """
            SELECT cell_id, shard_from, shard_to, datasource_ref, status
              FROM scheduler.cell_map
             ORDER BY cell_id
            """;

    /**
     * Loads the current rows.
     *
     * @return every row of {@code cell_map}
     * @throws RuntimeException if the rows cannot be read
     */
    List<CellRange> load();

    /**
     * Returns a source that reads {@code cell_map} from the control database.
     *
     * @param control the control database
     * @return the source
     */
    static CellMapSource fromControl(Database control) {
        Objects.requireNonNull(control, "control");
        return () -> control.jdbc()
                .sql(SELECT_CELL_MAP)
                .query((row, rowNumber) -> new CellRange(
                        row.getInt("cell_id"),
                        row.getInt("shard_from"),
                        row.getInt("shard_to"),
                        row.getString("datasource_ref"),
                        CellStatus.fromText(row.getString("status"))))
                .list();
    }
}
