package dev.kairos.persistence.jdbc;

import dev.kairos.persistence.error.TransientRetry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One PostgreSQL database (the control database or a cell) with its client, transactions and batch helpers.
 *
 * <p>Statements are written with named parameters only ({@code :name}); values are never concatenated into SQL. A
 * statement run through {@link #jdbc()} or {@link #batchUpdate} inside a transaction opened by {@link #transactions()}
 * joins that transaction; outside one it runs in auto-commit mode with the statement timeout of
 * {@link DatabaseOptions}.
 *
 * <p>Thread-safe.
 */
public final class Database {

    /** The name of the control database, also its {@code cell} metric tag. */
    public static final String CONTROL = "control";

    private static final Pattern NAME = Pattern.compile("^[a-z0-9-]{1,32}$");

    private final String name;
    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final DatabaseTransactions transactions;
    private final int batchSize;

    private Database(String name, DataSource dataSource, DatabaseOptions options, MeterRegistry meterRegistry) {
        this.name = name;
        this.dataSource = dataSource;
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(options.statementTimeoutSeconds());
        this.namedJdbc = new NamedParameterJdbcTemplate(template);
        this.jdbc = JdbcClient.create(namedJdbc);
        this.transactions = new DatabaseTransactions(
                name, dataSource, jdbc, options.transaction(), TransientRetry.withDefaults(), meterRegistry);
        this.batchSize = options.batchSize();
    }

    /**
     * Wraps a pool.
     *
     * @param name the database name: {@link #CONTROL} or {@link #cellName(int)}; also its metric tag
     * @param dataSource the pool; the caller keeps ownership and closes it
     * @param options timeouts, batch size and transaction defaults
     * @param meterRegistry where the transaction timer is registered
     * @return the database
     * @throws IllegalArgumentException if the name is not 1 to 32 lower-case letters, digits or hyphens
     */
    public static Database of(
            String name, DataSource dataSource, DatabaseOptions options, MeterRegistry meterRegistry) {
        Objects.requireNonNull(name, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A database name is 1 to 32 lower-case letters, digits or hyphens");
        }
        return new Database(
                name,
                Objects.requireNonNull(dataSource, "dataSource"),
                Objects.requireNonNull(options, "options"),
                Objects.requireNonNull(meterRegistry, "meterRegistry"));
    }

    /**
     * Returns the name of a cell database, which is also its {@code datasource_ref} in {@code cell_map}.
     *
     * @param cellId the cell number
     * @return for example {@code cell-0}
     * @throws IllegalArgumentException if {@code cellId} is negative
     */
    public static String cellName(int cellId) {
        if (cellId < 0) {
            throw new IllegalArgumentException("The cell number must not be negative");
        }
        return "cell-" + cellId;
    }

    /**
     * Returns the database name.
     *
     * @return {@link #CONTROL} or a cell name such as {@code cell-0}
     */
    public String name() {
        return name;
    }

    /**
     * Returns the pool.
     *
     * @return the data source
     */
    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * Returns the client for named-parameter statements.
     *
     * @return the client
     */
    public JdbcClient jdbc() {
        return jdbc;
    }

    /**
     * Returns the transaction runner of this database.
     *
     * @return the transactions
     */
    public DatabaseTransactions transactions() {
        return transactions;
    }

    /**
     * Runs one statement for many rows as JDBC batches of the configured size.
     *
     * <p>Use it for set-based writes whose rows each need their own parameters, such as outbox rows or settled runs.
     * Inside a transaction every batch joins it, so the rows commit or roll back together.
     *
     * @param <T> the row type
     * @param sql a statement with named parameters
     * @param rows the rows; may be empty
     * @param binder maps a row to the statement's parameters
     * @return the update count of each row, in row order
     */
    public <T> int[] batchUpdate(
            String sql, Collection<? extends T> rows, Function<? super T, ? extends SqlParameterSource> binder) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(binder, "binder");
        int[] counts = new int[rows.size()];
        List<SqlParameterSource> chunk = new ArrayList<>(Math.min(batchSize, rows.size()));
        int written = 0;
        for (T row : rows) {
            chunk.add(binder.apply(row));
            if (chunk.size() == batchSize) {
                written = flush(sql, chunk, counts, written);
            }
        }
        if (!chunk.isEmpty()) {
            flush(sql, chunk, counts, written);
        }
        return counts;
    }

    /**
     * Runs an {@code INSERT}, {@code UPDATE} or {@code DELETE} with a {@code RETURNING} clause and maps the returned
     * rows, so a write and the read of what it changed take one round trip.
     *
     * @param <T> the mapped type
     * @param sql a statement with named parameters and a {@code RETURNING} clause
     * @param parameters the statement's parameters
     * @param mapper maps one returned row
     * @return the mapped rows, in the order the database returned them
     */
    public <T> List<T> updateReturning(String sql, SqlParameterSource parameters, RowMapper<T> mapper) {
        return jdbc.sql(sql).paramSource(parameters).query(mapper).list();
    }

    private int flush(String sql, List<SqlParameterSource> chunk, int[] counts, int offset) {
        int[] chunkCounts = namedJdbc.batchUpdate(sql, chunk.toArray(SqlParameterSource[]::new));
        System.arraycopy(chunkCounts, 0, counts, offset, chunkCounts.length);
        chunk.clear();
        return offset + chunkCounts.length;
    }

    @Override
    public String toString() {
        return "Database[" + name + "]";
    }
}
