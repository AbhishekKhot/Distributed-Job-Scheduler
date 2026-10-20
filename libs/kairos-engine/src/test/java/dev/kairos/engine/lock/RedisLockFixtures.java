package dev.kairos.engine.lock;

import dev.kairos.testsupport.Awaits;
import dev.kairos.testsupport.KairosRedis;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;

/** Connectors for the lock tests, connected before they are handed out so the first command does not race the TCP setup. */
final class RedisLockFixtures {

    private RedisLockFixtures() {}

    static RedisConnector connector(KairosRedis redis, MeterRegistry registry) {
        return connected(RedisProperties.of(redis.host(), redis.port(), redis.username(), redis.password()), registry);
    }

    static RedisConnector connected(RedisProperties properties, MeterRegistry registry) {
        RedisConnector connector = RedisConnector.create(properties, registry, Clock.systemUTC());
        connector.connect();
        Awaits.until(() -> connector.topologyProbe().read().isPresent());
        return connector;
    }
}
