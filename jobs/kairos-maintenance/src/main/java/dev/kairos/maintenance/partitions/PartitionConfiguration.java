package dev.kairos.maintenance.partitions;

import dev.kairos.maintenance.partitions.application.PartitionManager;
import dev.kairos.maintenance.partitions.application.PartitionStore;
import dev.kairos.maintenance.partitions.application.PartitionTable;
import dev.kairos.maintenance.partitions.infrastructure.JdbcPartitionStore;
import dev.kairos.maintenance.partitions.infrastructure.PartitionProperties;
import dev.kairos.persistence.config.PersistenceConfiguration;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The maintenance deployment supplies owner connections; ordinary service credentials remain DML-only. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kairos.maintenance.partitions.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PartitionProperties.class)
@Import(PersistenceConfiguration.class)
public class PartitionConfiguration {
    @Bean
    public Clock partitionClock() {
        return Clock.systemUTC();
    }

    @Bean
    public PartitionManager partitionManager(
            DataSourceRegistry registry, PartitionProperties properties, Clock clock, MeterRegistry meters) {
        List<PartitionStore> stores = new ArrayList<>();
        stores.add(new JdbcPartitionStore(registry.control(), List.of(PartitionTable.AUDIT_LOG), properties, meters));
        registry.cells().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> stores.add(new JdbcPartitionStore(
                        entry.getValue(),
                        List.of(PartitionTable.RUN_HISTORY, PartitionTable.TASK_OUTPUTS),
                        properties,
                        meters)));
        return new PartitionManager(clock, stores);
    }
}
