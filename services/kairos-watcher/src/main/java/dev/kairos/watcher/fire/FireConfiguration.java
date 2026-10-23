package dev.kairos.watcher.fire;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.engine.run.RunCreationConfiguration;
import dev.kairos.engine.run.RunCreationProperties;
import dev.kairos.engine.run.RunCreator;
import dev.kairos.engine.run.RunFactory;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.watcher.lease.OwnedShards;
import dev.kairos.watcher.node.NodeIdentity;
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

/** Explicit watcher wiring for the fire poller and shared run creator. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kairos.watcher.enabled", havingValue = "true")
@Import(RunCreationConfiguration.class)
public class FireConfiguration {
    /** Listens for committed fire notifications while periodic polling remains the fallback. */
    @Bean(destroyMethod = "close")
    public FireNotificationListener fireNotificationListener(
            CellRouter cells, OwnedShards owned, NodeIdentity identity, Clock watcherClock, FirePoller poller) {
        return new FireNotificationListener(cells, owned, identity, watcherClock, poller);
    }

    /** Creates the configuration; Spring calls it. */
    public FireConfiguration() {}

    /** Creates the schedule repository. */
    @Bean
    public FireScheduleStore fireScheduleStore() {
        return new FireScheduleStore();
    }

    /** Creates job and workflow occurrences within the lease-guarded cell transaction. */
    @Bean
    public FireMaterializer fireMaterializer(
            @Qualifier("controlJdbcClient") JdbcClient control,
            JsonMapper json,
            KairosIdFactory ids,
            RunFactory factory,
            RunCreator creator) {
        return new JdbcFireMaterializer(control, json, ids, factory, creator);
    }

    /** Starts the one-second, eight-shard polling registration. */
    @Bean(destroyMethod = "close")
    public FirePoller firePoller(
            ShardedPoller scheduler,
            FireScheduleStore store,
            FireMaterializer materializer,
            Clock watcherClock,
            RunCreationProperties runSettings,
            MeterRegistry meters) {
        return new FirePoller(
                scheduler,
                store,
                materializer,
                watcherClock,
                runSettings.dispatchHorizon(),
                Duration.ofSeconds(10),
                meters);
    }
}
