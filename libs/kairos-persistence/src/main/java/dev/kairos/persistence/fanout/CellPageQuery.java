package dev.kairos.persistence.fanout;

import dev.kairos.persistence.cell.Cell;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads one page of a team-scoped list from one cell.
 *
 * <p>The query is the per-cell half of a keyset-paginated list: it returns the rows that sort strictly after
 * {@code after} (all rows when it is {@code null}), in the list's order, at most {@code limit} of them. It filters on
 * the caller's team like every team-scoped read. A typical statement:
 *
 * <pre>{@code
 * SELECT ... FROM scheduler.jobs
 *  WHERE team_id = :teamId AND (created_at, job_id) < (:afterCreatedAt, :afterJobId)
 *  ORDER BY created_at DESC, job_id DESC
 *  LIMIT :limit
 * }</pre>
 *
 * @param <T> the row type
 * @param <K> the sort key: unique and totally ordered, usually a timestamp plus the row id
 */
@FunctionalInterface
public interface CellPageQuery<T, K> {

    /**
     * Reads rows from one cell.
     *
     * @param cell the cell to read
     * @param after the sort key of the last row the client has seen, or {@code null} for the first page
     * @param limit the maximum number of rows to return
     * @return the rows after {@code after}, in the list's order
     */
    List<T> fetch(Cell cell, @Nullable K after, int limit);
}
