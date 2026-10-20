package dev.kairos.engine.lock;

import java.util.Optional;

/**
 * Reads the replication id and role of the Redis server ({@code INFO replication}, which the ACL allows), for the
 * Redis failover monitor (T-225).
 *
 * <p>Implementations are thread-safe.
 */
public interface RedisTopologyProbe {

    /**
     * Reads the current topology.
     *
     * @return the topology, or empty if Redis failed, timed out or answered with an unexpected reply
     */
    Optional<RedisTopology> read();
}
