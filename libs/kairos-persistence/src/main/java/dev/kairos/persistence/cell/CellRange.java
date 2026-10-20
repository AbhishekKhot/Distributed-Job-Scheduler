package dev.kairos.persistence.cell;

import dev.kairos.domain.shard.Shard;
import java.util.Objects;

/**
 * One row of {@code cell_map}: a contiguous range of shards held by one cell database.
 *
 * @param cellId the cell number
 * @param shardFrom the first shard of the range
 * @param shardTo the last shard of the range, inclusive
 * @param datasourceRef the name of the cell's data source, for example {@code cell-0}
 * @param status the cell's status
 */
public record CellRange(int cellId, int shardFrom, int shardTo, String datasourceRef, CellStatus status) {

    /**
     * Validates the row.
     *
     * @throws IllegalArgumentException if the cell number is negative, the range is empty or outside 0 to 255, or the
     *     data source name is blank
     */
    public CellRange {
        Objects.requireNonNull(datasourceRef, "datasourceRef");
        Objects.requireNonNull(status, "status");
        if (cellId < 0) {
            throw new IllegalArgumentException("The cell number must not be negative");
        }
        if (shardFrom < 0 || shardTo > Shard.MAX || shardFrom > shardTo) {
            throw new IllegalArgumentException("Cell " + cellId + " has an invalid shard range");
        }
        if (datasourceRef.isBlank()) {
            throw new IllegalArgumentException("Cell " + cellId + " has no data source name");
        }
    }

    /**
     * Tells whether the range holds a shard.
     *
     * @param shard the shard number
     * @return true if {@code shardFrom <= shard <= shardTo}
     */
    public boolean contains(int shard) {
        return shard >= shardFrom && shard <= shardTo;
    }
}
