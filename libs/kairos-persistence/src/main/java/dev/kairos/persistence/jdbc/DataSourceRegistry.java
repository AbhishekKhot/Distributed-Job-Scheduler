package dev.kairos.persistence.jdbc;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The databases a service talks to: the control database and the cells, one connection pool each.
 *
 * <p>Cells are numbered in configuration order, and cell {@code n} is named {@code cell-n}, which is the
 * {@code datasource_ref} that {@code cell_map} uses for it. Shards are never routed here; routing goes through the
 * {@code CellRouter}, which looks databases up by that name.
 *
 * <p>Pools are HikariCP pools named {@code kairos-<database>}, with Micrometer metrics ({@code hikaricp_connections_*},
 * tagged {@code pool}) for usage and wait time. They start without connecting, so a database that is down at startup
 * makes only its readiness check fail instead of stopping the service. {@link #close()} closes the pools this registry
 * created, and only those.
 *
 * <p>Thread-safe.
 */
public final class DataSourceRegistry implements AutoCloseable {

    private static final String POOL_PREFIX = "kairos-";

    private final Database control;
    private final Map<String, Database> cells;
    private final List<HikariDataSource> ownedPools;

    private DataSourceRegistry(Database control, Map<String, Database> cells, List<HikariDataSource> ownedPools) {
        this.control = control;
        this.cells = Collections.unmodifiableMap(new LinkedHashMap<>(cells));
        this.ownedPools = List.copyOf(ownedPools);
    }

    /**
     * Creates one pool per database.
     *
     * @param control the control database
     * @param cells the cell databases, in cell order; cell {@code n} is named {@code cell-n}
     * @param options pool, timeout and transaction settings shared by all databases
     * @param meterRegistry where pool and transaction metrics are registered
     * @return the registry, owning the pools
     * @throws IllegalArgumentException if no cell is configured
     */
    public static DataSourceRegistry connect(
            ConnectionSettings control,
            List<ConnectionSettings> cells,
            DatabaseOptions options,
            MeterRegistry meterRegistry) {
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("At least one cell database is required");
        }
        List<HikariDataSource> pools = new ArrayList<>();
        try {
            HikariDataSource controlPool = pool(Database.CONTROL, control, options, meterRegistry);
            pools.add(controlPool);
            Database controlDatabase = Database.of(Database.CONTROL, controlPool, options, meterRegistry);
            Map<String, Database> cellDatabases = new LinkedHashMap<>();
            for (int cellId = 0; cellId < cells.size(); cellId++) {
                String name = Database.cellName(cellId);
                HikariDataSource cellPool = pool(name, cells.get(cellId), options, meterRegistry);
                pools.add(cellPool);
                cellDatabases.put(name, Database.of(name, cellPool, options, meterRegistry));
            }
            return new DataSourceRegistry(controlDatabase, cellDatabases, pools);
        } catch (RuntimeException e) {
            pools.forEach(HikariDataSource::close);
            throw e;
        }
    }

    /**
     * Wraps databases whose pools someone else owns, for example the shared pools of the test containers.
     *
     * @param control the control database
     * @param cells the cell databases; each is registered under its {@link Database#name()}
     * @return the registry; closing it closes nothing
     * @throws IllegalArgumentException if no cell is given or two cells share a name
     */
    public static DataSourceRegistry of(Database control, List<Database> cells) {
        Objects.requireNonNull(control, "control");
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("At least one cell database is required");
        }
        Map<String, Database> byName = new LinkedHashMap<>();
        for (Database cell : cells) {
            if (byName.put(cell.name(), cell) != null) {
                throw new IllegalArgumentException("Two cells are named " + cell.name());
            }
        }
        return new DataSourceRegistry(control, byName, List.of());
    }

    /**
     * Returns the control database.
     *
     * @return the control database
     */
    public Database control() {
        return control;
    }

    /**
     * Returns the cell databases by name, in cell order.
     *
     * @return an unmodifiable map from {@code cell-n} to the database
     */
    public Map<String, Database> cells() {
        return cells;
    }

    /**
     * Looks up a cell database by its name, which is its {@code datasource_ref} in {@code cell_map}.
     *
     * @param name a name such as {@code cell-0}
     * @return the database, or empty if this service has no data source of that name
     */
    public Optional<Database> cell(String name) {
        return Optional.ofNullable(cells.get(name));
    }

    /**
     * Returns every database, control first, then the cells in cell order.
     *
     * @return an unmodifiable list
     */
    public List<Database> all() {
        List<Database> all = new ArrayList<>(cells.size() + 1);
        all.add(control);
        all.addAll(cells.values());
        return List.copyOf(all);
    }

    /** Closes the pools this registry created. */
    @Override
    public void close() {
        ownedPools.forEach(HikariDataSource::close);
    }

    private static HikariDataSource pool(
            String name, ConnectionSettings connection, DatabaseOptions options, MeterRegistry meterRegistry) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(POOL_PREFIX + name);
        config.setJdbcUrl(connection.url());
        config.setUsername(connection.username());
        config.setPassword(connection.password());
        config.setMaximumPoolSize(connection.maximumPoolSize());
        config.setMinimumIdle(Math.min(options.minimumIdle(), connection.maximumPoolSize()));
        config.setConnectionTimeout(options.connectionTimeout().toMillis());
        config.setIdleTimeout(options.idleTimeout().toMillis());
        config.setMaxLifetime(options.maxLifetime().toMillis());
        // Start without a connection: a database that is down makes readiness fail instead of the whole service.
        config.setInitializationFailTimeout(-1);
        config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));
        return new HikariDataSource(config);
    }
}
