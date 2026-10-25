package dev.kairos.api.jobs.infrastructure;

import dev.kairos.api.jobs.application.JobCreationGuard;
import dev.kairos.api.support.problem.ApiException;
import dev.kairos.domain.id.TeamId;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * Uses a team-scoped control-database session advisory lock for quota admission. The connection
 * stays in autocommit: no control transaction spans the independent cell commits. Contenders
 * release their pooled connection between attempts, so they cannot starve the lock holder's reads.
 */
public final class JdbcJobCreationGuard implements JobCreationGuard {
    private static final long WAIT_NANOS = Duration.ofSeconds(5).toNanos();
    private static final String TRY_LOCK = "SELECT pg_try_advisory_lock(hashtextextended(?, 0))";
    private static final String UNLOCK = "SELECT pg_advisory_unlock(hashtextextended(?, 0))";
    private final DataSource control;

    public JdbcJobCreationGuard(DataSource control) {
        this.control = Objects.requireNonNull(control, "control");
    }

    @Override
    public <T> T execute(TeamId team, Supplier<T> work) {
        String key = "kairos:job-create:" + team.value();
        long started = System.nanoTime();
        try {
            while (true) {
                Connection acquired = tryAcquire(key);
                if (acquired != null) {
                    try (acquired) {
                        try {
                            return work.get();
                        } finally {
                            // Never return a connection to the pool with a session lock still held.
                            try {
                                if (!query(acquired, UNLOCK, key))
                                    throw new SQLException("Job admission lock was lost");
                            } catch (SQLException failure) {
                                discard(acquired, failure);
                                throw failure;
                            }
                        }
                    }
                }
                if (System.nanoTime() - started >= WAIT_NANOS) throw ApiException.unavailable(Duration.ofSeconds(1));
                Thread.sleep(10);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable(Duration.ofSeconds(1));
        } catch (SQLException failure) {
            ApiException unavailable = ApiException.unavailable(Duration.ofSeconds(1));
            unavailable.initCause(failure);
            throw unavailable;
        }
    }

    private @Nullable Connection tryAcquire(String key) throws SQLException {
        Connection connection = control.getConnection();
        try {
            if (query(connection, TRY_LOCK, key)) return connection;
        } catch (SQLException failure) {
            // An uncertain acquisition must also close the physical session to release any lock.
            discard(connection, failure);
            connection.close();
            throw failure;
        }
        connection.close();
        return null;
    }

    private static boolean query(Connection connection, String sql, String key) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            statement.setString(1, key);
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static void discard(Connection connection, SQLException failure) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException closing) {
            failure.addSuppressed(closing);
        }
    }
}
