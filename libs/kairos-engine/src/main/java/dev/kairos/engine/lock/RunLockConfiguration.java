package dev.kairos.engine.lock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring of the Redis run lock, sent markers and topology probe. Services that need them (the executor and the
 * watcher) import it explicitly with {@code @Import(RunLockConfiguration.class)}; it is not an auto-configuration, so
 * nothing connects to Redis unless a service asks for it.
 *
 * <p>Beans: the {@link RedisConnector} (connecting starts in the background at startup, so a service starts even
 * while Redis is down and its operations fail closed until Redis is back; closed on shutdown), the {@link RunLockPort},
 * the {@link SentMarkerPort} and the {@link RedisTopologyProbe}. Settings come from {@code kairos.redis.*}
 * ({@link RedisProperties}).
 *
 * <p>A {@link Clock} bean and a {@link MeterRegistry} are used when the service defines them; otherwise the UTC system
 * clock and a private registry are used.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisProperties.class)
public class RunLockConfiguration {

    /** The environment variable holding the pod name in Kubernetes and the container id in compose. */
    static final String HOSTNAME_VARIABLE = "HOSTNAME";

    private static final int RANDOM_NODE_ID_BYTES = 8;

    /** Creates the configuration; Spring calls it. */
    public RunLockConfiguration() {}

    /**
     * The shared Redis connection, connecting in the background.
     *
     * @param properties the Redis settings
     * @param meterRegistry the service's meter registry, if any
     * @param clock the service's clock, if any
     * @return the connector
     */
    @Bean(destroyMethod = "close")
    public RedisConnector redisConnector(
            RedisProperties properties, ObjectProvider<MeterRegistry> meterRegistry, ObjectProvider<Clock> clock) {
        RedisConnector connector = RedisConnector.create(
                properties,
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new),
                clock.getIfAvailable(Clock::systemUTC));
        connector.connect();
        return connector;
    }

    /**
     * The run lock.
     *
     * @param connector the Redis connection
     * @param properties the Redis settings, for the node id
     * @return the run lock
     */
    @Bean
    public RunLockPort runLockPort(RedisConnector connector, RedisProperties properties) {
        return connector.runLock(nodeId(properties.nodeId(), System.getenv(HOSTNAME_VARIABLE), new SecureRandom()));
    }

    /**
     * The sent markers.
     *
     * @param connector the Redis connection
     * @return the sent markers
     */
    @Bean
    public SentMarkerPort sentMarkerPort(RedisConnector connector) {
        return connector.sentMarkers();
    }

    /**
     * The topology probe for the failover monitor.
     *
     * @param connector the Redis connection
     * @return the probe
     */
    @Bean
    public RedisTopologyProbe redisTopologyProbe(RedisConnector connector) {
        return connector.topologyProbe();
    }

    /**
     * Chooses the node id written into lock tokens: the configured one, else the host name if it is a valid node id,
     * else a random one. The environment is untrusted input, so the host name is validated before use.
     */
    static String nodeId(@Nullable String configured, @Nullable String hostname, SecureRandom random) {
        if (configured != null) {
            return LockToken.requireNodeId(configured);
        }
        if (hostname != null) {
            try {
                return LockToken.requireNodeId(hostname);
            } catch (IllegalArgumentException e) {
                // Fall through to a random id.
            }
        }
        byte[] bytes = new byte[RANDOM_NODE_ID_BYTES];
        random.nextBytes(bytes);
        return "node-" + HexFormat.of().formatHex(bytes);
    }
}
