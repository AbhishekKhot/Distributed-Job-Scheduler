package dev.kairos.persistence.cell;

import dev.kairos.domain.shard.Shard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A complete, validated shard-to-cell layout: every one of the 256 shards belongs to exactly one cell.
 *
 * <p>Built from the rows of {@code cell_map}. A layout with a gap, an overlap, or two rows for the same cell is
 * rejected, so a half-edited {@code cell_map} can never route a shard to the wrong database. Lookups are a single array
 * read.
 *
 * <p>Immutable and thread-safe.
 */
public final class CellLayout {

    private final List<CellRange> ranges;
    private final CellRange[] byShard;

    private CellLayout(List<CellRange> ranges, CellRange[] byShard) {
        this.ranges = ranges;
        this.byShard = byShard;
    }

    /**
     * Validates rows and builds the layout.
     *
     * @param rows the rows of {@code cell_map}, in any order
     * @return the layout
     * @throws IllegalArgumentException if a shard is held by no cell or by two, or a cell number repeats
     */
    public static CellLayout of(List<CellRange> rows) {
        List<CellRange> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt(CellRange::cellId));
        CellRange[] byShard = new CellRange[Shard.COUNT];
        for (int i = 0; i < sorted.size(); i++) {
            CellRange range = sorted.get(i);
            if (i > 0 && sorted.get(i - 1).cellId() == range.cellId()) {
                throw new IllegalArgumentException("cell_map lists cell " + range.cellId() + " twice");
            }
            for (int shard = range.shardFrom(); shard <= range.shardTo(); shard++) {
                CellRange previous = byShard[shard];
                if (previous != null) {
                    throw new IllegalArgumentException("cell_map assigns shard " + shard + " to cells "
                            + previous.cellId() + " and " + range.cellId());
                }
                byShard[shard] = range;
            }
        }
        for (int shard = 0; shard < Shard.COUNT; shard++) {
            if (byShard[shard] == null) {
                throw new IllegalArgumentException("cell_map assigns shard " + shard + " to no cell");
            }
        }
        return new CellLayout(List.copyOf(sorted), byShard);
    }

    /**
     * Returns the range that holds a shard.
     *
     * @param shard the shard number, 0 to 255
     * @return its range
     * @throws IllegalArgumentException if the shard is outside 0 to 255
     */
    public CellRange rangeOf(int shard) {
        if (shard < 0 || shard > Shard.MAX) {
            throw new IllegalArgumentException("Shard must be between 0 and " + Shard.MAX);
        }
        return byShard[shard];
    }

    /**
     * Returns every range, ordered by cell number.
     *
     * @return an unmodifiable list
     */
    public List<CellRange> ranges() {
        return ranges;
    }
}
