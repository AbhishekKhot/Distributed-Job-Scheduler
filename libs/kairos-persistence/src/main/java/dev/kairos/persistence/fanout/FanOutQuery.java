package dev.kairos.persistence.fanout;

import dev.kairos.persistence.cell.Cell;
import dev.kairos.persistence.cell.CellRouter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Reads a team-scoped list from every cell and merges the results into one keyset-paginated page.
 *
 * <p>A team's jobs and workflows are spread over all cells (their shard depends on the name), so list endpoints read
 * every cell. Each cell is asked, in parallel on virtual threads, for up to {@code limit + 1} rows after the cursor key;
 * the sorted results are merged k-way by the list's order, the first {@code limit} rows form the page, and the key of
 * the last of them becomes the next cursor if any row is left over. Because the key is unique and totally ordered,
 * continuing from it never skips or repeats a row, whichever cells the rows come from.
 *
 * <p>Each cell's rows must already be in the list's order (the query's {@code ORDER BY}); a cell that returns rows out
 * of order fails the call instead of producing a wrong page. The whole fan-out has a deadline; when a cell does not
 * answer in time the call fails rather than returning a page with a cell's rows silently missing.
 *
 * <p>Thread-safe.
 */
public final class FanOutQuery {

    /** The largest page a caller may ask for. The API allows at most 200. */
    public static final int MAX_LIMIT = 1_000;

    /** The default deadline of one fan-out read. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final CellRouter router;
    private final Duration timeout;

    /**
     * Creates the helper.
     *
     * @param router supplies every cell
     * @param timeout the deadline of one fan-out read across all cells; positive
     * @throws IllegalArgumentException if the timeout is not positive
     */
    public FanOutQuery(CellRouter router, Duration timeout) {
        this.router = Objects.requireNonNull(router, "router");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("The timeout must be positive");
        }
        this.timeout = timeout;
    }

    /**
     * Reads one page across every cell.
     *
     * @param <T> the row type
     * @param <K> the sort key type
     * @param after the key of the last row of the previous page, or {@code null} for the first page
     * @param limit the page size, 1 to {@value #MAX_LIMIT}
     * @param query reads rows after a key from one cell, in list order
     * @param order the list order; must agree with the query's {@code ORDER BY} and be total
     * @param keyOf extracts the sort key of a row
     * @return the page
     * @throws IllegalArgumentException if the limit is out of range
     * @throws IllegalStateException if a cell returns rows out of order, or more than asked for
     * @throws FanOutException if a cell fails or the deadline passes
     */
    public <T, K> FanOutPage<T, K> list(
            @Nullable K after,
            int limit,
            CellPageQuery<T, K> query,
            Comparator<? super T> order,
            Function<? super T, ? extends K> keyOf) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(keyOf, "keyOf");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("The limit must be between 1 and " + MAX_LIMIT);
        }
        // One extra row tells whether another page follows.
        int fetchSize = limit + 1;
        List<List<T>> perCell = fetchAll(router.allCells(), after, fetchSize, query);
        for (List<T> rows : perCell) {
            requireSorted(rows, fetchSize, order);
        }
        List<T> merged = merge(perCell, fetchSize, order);
        if (merged.size() <= limit) {
            return new FanOutPage<>(merged, null);
        }
        List<T> page = merged.subList(0, limit);
        return new FanOutPage<>(page, keyOf.apply(page.get(limit - 1)));
    }

    private <T, K> List<List<T>> fetchAll(
            List<Cell> cells, @Nullable K after, int fetchSize, CellPageQuery<T, K> query) {
        if (cells.size() == 1) {
            return List.of(query.fetch(cells.get(0), after, fetchSize));
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<T>>> futures = new ArrayList<>(cells.size());
            for (Cell cell : cells) {
                futures.add(executor.submit(() -> query.fetch(cell, after, fetchSize)));
            }
            List<List<T>> results = new ArrayList<>(cells.size());
            for (int i = 0; i < futures.size(); i++) {
                results.add(await(futures, i, cells.get(i), deadline));
            }
            return results;
        }
    }

    private static <T> List<T> await(List<Future<List<T>>> futures, int index, Cell cell, long deadline) {
        try {
            return futures.get(index).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            futures.forEach(future -> future.cancel(true));
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new FanOutException("Reading cell " + cell.id() + " failed", cause == null ? e : cause);
        } catch (TimeoutException e) {
            futures.forEach(future -> future.cancel(true));
            throw new FanOutException("Cell " + cell.id() + " did not answer in time", e);
        } catch (InterruptedException e) {
            futures.forEach(future -> future.cancel(true));
            Thread.currentThread().interrupt();
            throw new FanOutException("Interrupted while reading cell " + cell.id(), e);
        }
    }

    private static <T> void requireSorted(List<T> rows, int fetchSize, Comparator<? super T> order) {
        if (rows.size() > fetchSize) {
            throw new IllegalStateException("A cell returned more rows than the limit");
        }
        for (int i = 1; i < rows.size(); i++) {
            if (order.compare(rows.get(i - 1), rows.get(i)) > 0) {
                throw new IllegalStateException("A cell returned rows out of list order");
            }
        }
    }

    private static <T> List<T> merge(List<List<T>> perCell, int fetchSize, Comparator<? super T> order) {
        // Ties between cells (which a total order never has) fall back to cell order, keeping the merge stable.
        Comparator<Cursor<T>> byHead =
                Comparator.<Cursor<T>, T>comparing(Cursor::head, order).thenComparingInt(Cursor::cellIndex);
        PriorityQueue<Cursor<T>> heads = new PriorityQueue<>(Math.max(1, perCell.size()), byHead);
        for (int cellIndex = 0; cellIndex < perCell.size(); cellIndex++) {
            List<T> rows = perCell.get(cellIndex);
            if (!rows.isEmpty()) {
                heads.add(new Cursor<>(rows, cellIndex, 0));
            }
        }
        List<T> merged = new ArrayList<>(fetchSize);
        while (merged.size() < fetchSize && !heads.isEmpty()) {
            Cursor<T> cursor = heads.poll();
            merged.add(cursor.head());
            if (cursor.position() + 1 < cursor.rows().size()) {
                heads.add(new Cursor<>(cursor.rows(), cursor.cellIndex(), cursor.position() + 1));
            }
        }
        return merged;
    }

    /** The next unmerged row of one cell's result. */
    private record Cursor<T>(List<T> rows, int cellIndex, int position) {

        T head() {
            return rows.get(position);
        }
    }
}
