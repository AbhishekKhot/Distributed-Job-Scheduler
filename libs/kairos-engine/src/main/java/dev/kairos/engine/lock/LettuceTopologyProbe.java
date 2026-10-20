package dev.kairos.engine.lock;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link RedisTopologyProbe} on Lettuce: {@code INFO replication}, parsed by {@link RedisTopology#parse}.
 *
 * <p>Thread-safe.
 */
final class LettuceTopologyProbe implements RedisTopologyProbe {

    private final RedisConnector redis;

    LettuceTopologyProbe(RedisConnector redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public Optional<RedisTopology> read() {
        try {
            String info = redis.call(commands -> commands.info("replication"));
            return info == null ? Optional.empty() : RedisTopology.parse(info);
        } catch (RedisUnavailableException e) {
            return Optional.empty();
        }
    }
}
