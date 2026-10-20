package dev.kairos.persistence.cell;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.id.ShardedId;
import dev.kairos.domain.shard.Shard;
import dev.kairos.persistence.jdbc.Database;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * Turns a shard, or an id that embeds one, into the cell database that holds it (LLD section 2, HLD section 8).
 *
 * <p>Every cell-scoped database call goes through this router. The layout comes from {@code cell_map} in the control
 * database and is cached: after {@code refreshInterval} (30s by default) the next lookup triggers a reload in the
 * background while lookups keep using the current layout, so the hot path never waits for the control database. If a
 * reload fails, or returns a layout that is incomplete or names a data source this service does not have, the current
 * layout stays in use and the reload is tried again on a later lookup; a control database outage therefore never stops
 * traffic to the cells. Only the very first load happens on the caller's thread, and its failure is thrown to the
 * caller.
 *
 * <p>Thread-safe.
 */
public final class CellRouter {

    /** The default interval after which {@code cell_map} is read again. */
    public static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(30);

    private static final String KEY = "cell_map";

    private final CellMapSource source;
    private final Map<String, Database> databases;
    private final LoadingCache<String, Routes> routes;

    /**
     * Creates a router that reloads the layout on the common pool.
     *
     * @param source where the layout is read from
     * @param databases the cell databases of this service, by name ({@code datasource_ref})
     * @param refreshInterval how long a loaded layout is used before it is read again
     * @param clock the clock that ages the cached layout
     */
    public CellRouter(CellMapSource source, Map<String, Database> databases, Duration refreshInterval, Clock clock) {
        this(source, databases, refreshInterval, clock, ForkJoinPool.commonPool());
    }

    /**
     * Creates a router.
     *
     * @param source where the layout is read from
     * @param databases the cell databases of this service, by name ({@code datasource_ref})
     * @param refreshInterval how long a loaded layout is used before it is read again; positive
     * @param clock the clock that ages the cached layout
     * @param refreshExecutor where background reloads run
     * @throws IllegalArgumentException if no database is given or the interval is not positive
     */
    public CellRouter(
            CellMapSource source,
            Map<String, Database> databases,
            Duration refreshInterval,
            Clock clock,
            Executor refreshExecutor) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(refreshInterval, "refreshInterval");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(refreshExecutor, "refreshExecutor");
        if (databases.isEmpty()) {
            throw new IllegalArgumentException("At least one cell database is required");
        }
        if (refreshInterval.isNegative() || refreshInterval.isZero()) {
            throw new IllegalArgumentException("The refresh interval must be positive");
        }
        this.databases = Map.copyOf(databases);
        Ticker ticker = () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());
        this.routes = Caffeine.newBuilder()
                .refreshAfterWrite(refreshInterval)
                .ticker(ticker)
                .executor(refreshExecutor)
                .build(key -> load());
    }

    /**
     * Returns the cell that holds a shard.
     *
     * @param shard the shard number, 0 to 255
     * @return the cell
     * @throws IllegalArgumentException if the shard is outside 0 to 255
     * @throws RuntimeException if the layout has never been loaded and cannot be loaded now
     */
    public Cell forShard(int shard) {
        return current().forShard(shard);
    }

    /**
     * Returns the cell that holds a shard.
     *
     * @param shard the shard
     * @return the cell
     * @throws RuntimeException if the layout has never been loaded and cannot be loaded now
     */
    public Cell forShard(Shard shard) {
        return forShard(shard.value());
    }

    /**
     * Returns the cell that holds the shard embedded in an id (its low 8 bits).
     *
     * @param id a job, workflow, run, workflow-run or DLQ id
     * @return the cell
     * @throws IllegalArgumentException if {@code id} is not a Kairos id
     * @throws RuntimeException if the layout has never been loaded and cannot be loaded now
     */
    public Cell forId(UUID id) {
        return forShard(KairosId.shardOf(id));
    }

    /**
     * Returns the cell that holds the shard embedded in a typed id.
     *
     * @param id a job, workflow, run, workflow-run or DLQ id
     * @return the cell
     * @throws RuntimeException if the layout has never been loaded and cannot be loaded now
     */
    public Cell forId(ShardedId id) {
        return forShard(id.shard());
    }

    /**
     * Returns every cell, ordered by cell number, for fan-out reads across the whole layout.
     *
     * @return an unmodifiable list
     * @throws RuntimeException if the layout has never been loaded and cannot be loaded now
     */
    public List<Cell> allCells() {
        return current().cells();
    }

    /**
     * Returns the layout currently in use.
     *
     * @return the layout
     * @throws RuntimeException if the layout has never been loaded and cannot be loaded now
     */
    public CellLayout layout() {
        return current().layout();
    }

    private Routes current() {
        return routes.get(KEY);
    }

    private Routes load() {
        CellLayout layout = CellLayout.of(source.load());
        List<Cell> cells = new ArrayList<>(layout.ranges().size());
        List<String> missing = new ArrayList<>();
        for (CellRange range : layout.ranges()) {
            Database database = databases.get(range.datasourceRef());
            if (database == null) {
                missing.add(range.datasourceRef());
            } else {
                cells.add(new Cell(range, database));
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "cell_map names data sources this service is not configured with: " + missing);
        }
        Cell[] byShard = new Cell[Shard.COUNT];
        for (Cell cell : cells) {
            for (int shard = cell.range().shardFrom(); shard <= cell.range().shardTo(); shard++) {
                byShard[shard] = cell;
            }
        }
        return new Routes(layout, List.copyOf(cells), byShard);
    }

    /**
     * A loaded layout with its cells, ordered by cell number, and the cell of every shard.
     *
     * @param layout the validated layout
     * @param cells the cells, ordered by cell number
     * @param byShard the cell of each shard, indexed by shard number; never modified after construction
     */
    @SuppressWarnings("ArrayRecordComponent") // Private, built once in load() and only read afterwards.
    private record Routes(CellLayout layout, List<Cell> cells, Cell[] byShard) {

        Cell forShard(int shard) {
            if (shard < 0 || shard > Shard.MAX) {
                throw new IllegalArgumentException("Shard must be between 0 and " + Shard.MAX);
            }
            return byShard[shard];
        }
    }
}
