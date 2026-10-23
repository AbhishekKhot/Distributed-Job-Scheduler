package dev.kairos.watcher.node;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Maintains watcher heartbeats in the shared control database. */
public final class NodeRegistry implements AutoCloseable {
    /** Inserts or refreshes the current process row. */
    public static final String UPSERT = """
            INSERT INTO scheduler.scheduler_nodes (node_id, roles, version, started_at, heartbeat_at)
            VALUES (:node, :role, :version, :started, clock_timestamp())
            ON CONFLICT (node_id) DO UPDATE
               SET roles = EXCLUDED.roles, version = EXCLUDED.version, heartbeat_at = clock_timestamp()
            """;

    private final JdbcClient control;
    private final NodeIdentity identity;
    private final Duration staleAfter;

    /**
     * Creates the registry.
     * @param control the shared control database client
     * @param identity this process identity
     * @param staleAfter heartbeat age after which a node is considered dead
     */
    public NodeRegistry(JdbcClient control, NodeIdentity identity, Duration staleAfter) {
        this.control = Objects.requireNonNull(control, "control");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
        if (staleAfter.isNegative() || staleAfter.isZero())
            throw new IllegalArgumentException("staleAfter must be positive");
    }

    /** Registers or refreshes this process and returns the number of live watchers. */
    public int heartbeatAndCountWatchers() {
        control.sql(UPSERT)
                .param("node", identity.nodeId())
                .param("role", identity.role())
                .param("version", identity.version())
                .param("started", identity.startedAt().atOffset(ZoneOffset.UTC))
                .update();
        control.sql(
                        "DELETE FROM scheduler.scheduler_nodes WHERE heartbeat_at <= clock_timestamp() - (:staleMs * interval '1 millisecond') AND node_id <> :node")
                .param("staleMs", staleAfter.toMillis())
                .param("node", identity.nodeId())
                .update();
        return control.sql(
                        "SELECT count(*) FROM scheduler.scheduler_nodes WHERE roles = :role AND heartbeat_at > clock_timestamp() - (:staleMs * interval '1 millisecond')")
                .param("role", identity.role())
                .param("staleMs", staleAfter.toMillis())
                .query(Long.class)
                .single()
                .intValue();
    }

    /** Returns the node identity. */
    public NodeIdentity identity() {
        return identity;
    }

    /** Removes this process row after releasing its cell leases. */
    @Override
    public void close() {
        control.sql("DELETE FROM scheduler.scheduler_nodes WHERE node_id = :node")
                .param("node", identity.nodeId())
                .update();
    }
}
