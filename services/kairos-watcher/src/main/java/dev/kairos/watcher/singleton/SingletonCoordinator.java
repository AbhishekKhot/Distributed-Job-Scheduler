package dev.kairos.watcher.singleton;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Holds the process-wide watcher singleton advisory lock on its own PostgreSQL connection. */
public final class SingletonCoordinator implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SingletonCoordinator.class);
    private static final int LOCK_CLASS = 1_263_778_124;
    private static final int LOCK_ID = 226;
    private final DataSource dataSource;
    private Connection connection;

    /** Creates a coordinator backed by the control database connection pool. */
    public SingletonCoordinator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Attempts to hold or acquire the singleton lock; unavailable databases fail closed. */
    public synchronized boolean isLeader() {
        if (connection != null) {
            try {
                if (!connection.isClosed() && connection.isValid(2)) return true;
            } catch (SQLException failure) {
                LOG.warn(
                        "Singleton lock connection failed: {}",
                        failure.getClass().getSimpleName());
            }
            closeConnection();
        }
        Connection candidate = null;
        try {
            candidate = dataSource.getConnection();
            candidate.setAutoCommit(true);
            try (PreparedStatement statement = candidate.prepareStatement("SELECT pg_try_advisory_lock(?, ?)");
                    ResultSet result = bindAndQuery(statement)) {
                if (result.next() && result.getBoolean(1)) {
                    connection = candidate;
                    return true;
                }
            }
        } catch (SQLException failure) {
            LOG.warn("Singleton lock acquisition failed: {}", failure.getClass().getSimpleName());
        }
        close(candidate);
        return false;
    }

    private ResultSet bindAndQuery(PreparedStatement statement) throws SQLException {
        statement.setInt(1, LOCK_CLASS);
        statement.setInt(2, LOCK_ID);
        return statement.executeQuery();
    }

    private void closeConnection() {
        Connection held = connection;
        connection = null;
        close(held);
    }

    private static void close(Connection candidate) {
        if (candidate == null) return;
        try {
            candidate.close();
        } catch (SQLException failure) {
            LOG.debug(
                    "Could not close singleton lock connection: {}",
                    failure.getClass().getSimpleName());
        }
    }

    /** Releases the advisory lock before returning the dedicated connection to the pool. */
    @Override
    public synchronized void close() {
        if (connection == null) return;
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)");
                ResultSet result = bindAndQuery(statement)) {
            if (result.next() && !result.getBoolean(1)) {
                LOG.warn("Singleton advisory lock was not held during shutdown");
            }
        } catch (SQLException failure) {
            LOG.warn(
                    "Singleton advisory lock release failed: {}",
                    failure.getClass().getSimpleName());
        } finally {
            closeConnection();
        }
    }
}
