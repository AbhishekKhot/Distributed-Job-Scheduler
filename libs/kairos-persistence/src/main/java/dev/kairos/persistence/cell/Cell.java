package dev.kairos.persistence.cell;

import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseTransactions;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A cell database as routing sees it: its {@code cell_map} row and the database that serves it.
 *
 * @param range the cell's row in {@code cell_map}
 * @param database the cell's database
 */
public record Cell(CellRange range, Database database) {

    /**
     * Validates that the database is the one the row names.
     *
     * @throws IllegalArgumentException if the database name differs from the row's {@code datasource_ref}
     */
    public Cell {
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(database, "database");
        if (!range.datasourceRef().equals(database.name())) {
            throw new IllegalArgumentException("Cell " + range.cellId() + " expects data source "
                    + range.datasourceRef() + ", not " + database.name());
        }
    }

    /**
     * Returns the cell number.
     *
     * @return the cell number
     */
    public int id() {
        return range.cellId();
    }

    /**
     * Returns the cell's status in {@code cell_map}.
     *
     * @return the status
     */
    public CellStatus status() {
        return range.status();
    }

    /**
     * Returns the client of the cell database.
     *
     * @return the client
     */
    public JdbcClient jdbc() {
        return database.jdbc();
    }

    /**
     * Returns the transaction runner of the cell database.
     *
     * @return the transactions
     */
    public DatabaseTransactions transactions() {
        return database.transactions();
    }
}
