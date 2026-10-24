package dev.kairos.watcher.outbox;

import dev.kairos.messaging.client.KafkaPublisher;
import dev.kairos.messaging.config.MessagingConfiguration;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Watcher wiring for the transactional outbox relay. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kairos.watcher.enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxRelayProperties.class)
@Import(MessagingConfiguration.class)
public class OutboxRelayConfiguration {
    /** Creates the configuration; Spring calls it. */
    public OutboxRelayConfiguration() {}

    /** Creates the cell outbox repository. */
    @Bean
    public OutboxStore outboxStore() {
        return new OutboxStore();
    }

    /** Creates cell backlog and publish metrics. */
    @Bean
    public OutboxMetrics outboxMetrics(MeterRegistry meters) {
        return new OutboxMetrics(meters);
    }

    /** Shares one optional token bucket across this watcher's shard threads. */
    @Bean
    public RelayRateLimiter relayRateLimiter(OutboxRelayProperties properties) {
        return new RelayRateLimiter(properties.maxDispatchesPerSecond());
    }

    /** Starts the 100 ms, lease-guarded relay. */
    @Bean(destroyMethod = "close")
    public OutboxRelay outboxRelay(
            ShardedPoller scheduler,
            OutboxStore store,
            KafkaPublisher publisher,
            RelayRateLimiter limiter,
            Clock watcherClock,
            OutboxMetrics metrics) {
        return new OutboxRelay(scheduler, store, publisher, limiter, watcherClock, metrics);
    }
}
