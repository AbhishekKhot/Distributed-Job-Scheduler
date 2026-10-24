package dev.kairos.watcher.recovery;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.retry.RetryPlanner;
import dev.kairos.engine.lock.RedisTopologyProbe;
import dev.kairos.engine.lock.RunLockConfiguration;
import dev.kairos.engine.lock.RunLockPort;
import dev.kairos.engine.lock.SentMarkerPort;
import dev.kairos.engine.outbox.OutboxConfiguration;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.watcher.delayed.DelayedDispatchLoader;
import dev.kairos.watcher.support.ShardedPoller;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Watcher wiring for lease recovery, stuck queued runs and Redis failover quarantine. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kairos.watcher.enabled", havingValue = "true")
@Import({OutboxConfiguration.class, RunLockConfiguration.class})
public class RecoveryConfiguration {
    /** Creates the configuration; Spring calls it. */
    public RecoveryConfiguration() {}

    /** Creates the recovery run repository. */
    @Bean
    public RecoveryRunStore recoveryRunStore() {
        return new RecoveryRunStore();
    }

    /** Creates the pinned retry policy loader. */
    @Bean
    public RecoveryPolicyLoader recoveryPolicyLoader(
            @Qualifier("controlJdbcClient") JdbcClient control, JsonMapper json) {
        return new RecoveryPolicyLoader(control, json);
    }

    /** Starts the two-second Redis topology monitor. */
    @Bean
    public RedisFailoverMonitor redisFailoverMonitor(
            RedisTopologyProbe probe,
            @Qualifier("controlJdbcClient") JdbcClient control,
            Clock watcherClock,
            MeterRegistry meters) {
        return new RedisFailoverMonitor(probe, control, watcherClock, meters);
    }

    /** Starts the five-second expired executor lease sweeper. */
    @Bean(destroyMethod = "close")
    public LeaseSweeper leaseSweeper(
            ShardedPoller scheduler,
            RecoveryRunStore store,
            RunLockPort locks,
            SentMarkerPort markers,
            RedisFailoverMonitor failover,
            RecoveryPolicyLoader policies,
            DelayedDispatchLoader loader,
            KairosIdFactory ids,
            OutboxWriter outbox,
            Clock watcherClock,
            MeterRegistry meters) {
        return new LeaseSweeper(
                scheduler,
                store,
                locks,
                markers,
                failover,
                policies,
                loader,
                new RetryPlanner(watcherClock, ThreadLocalRandom::current),
                ids,
                outbox,
                watcherClock,
                meters);
    }

    /** Starts the one-minute stuck queued sweeper. */
    @Bean(destroyMethod = "close")
    public StuckQueuedSweeper stuckQueuedSweeper(
            ShardedPoller scheduler,
            RecoveryRunStore store,
            DelayedDispatchLoader loader,
            OutboxWriter outbox,
            Clock watcherClock,
            MeterRegistry meters) {
        return new StuckQueuedSweeper(scheduler, store, loader, outbox, watcherClock, meters);
    }
}
