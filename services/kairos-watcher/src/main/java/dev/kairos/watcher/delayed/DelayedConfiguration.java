package dev.kairos.watcher.delayed;

import dev.kairos.engine.lock.RedisConnector;
import dev.kairos.engine.lock.RunLockConfiguration;
import dev.kairos.engine.outbox.OutboxConfiguration;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Explicit watcher wiring for delayed dispatch. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kairos.watcher.enabled", havingValue = "true")
@Import({OutboxConfiguration.class, RunLockConfiguration.class})
public class DelayedConfiguration {
    /** Creates the configuration; Spring calls it. */
    public DelayedConfiguration() {}

    /** Creates the delayed row repository. */
    @Bean
    public DelayedRunStore delayedRunStore() {
        return new DelayedRunStore();
    }

    /** Loads immutable definition and endpoint versions for dispatch. */
    @Bean
    public DelayedDispatchLoader delayedDispatchLoader(
            @Qualifier("controlJdbcClient") JdbcClient control, JsonMapper json) {
        return new DelayedDispatchLoader(control, json);
    }

    /** Creates the shared Redis pacing windows used by all watcher instances. */
    @Bean
    public DelayedPacer delayedPacer(RedisConnector redis) {
        return new DelayedPacer(redis);
    }

    /** Starts one-second delayed polling. */
    @Bean(destroyMethod = "close")
    public DelayedPoller delayedPoller(
            ShardedPoller scheduler,
            DelayedRunStore store,
            DelayedDispatchSource loader,
            DelayedPacer pacer,
            OutboxWriter outbox,
            Clock watcherClock,
            MeterRegistry meters) {
        return new DelayedPoller(scheduler, store, loader, pacer, outbox, watcherClock, Duration.ofSeconds(15), meters);
    }
}
