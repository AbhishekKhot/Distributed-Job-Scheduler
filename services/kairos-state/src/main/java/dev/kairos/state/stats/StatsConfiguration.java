package dev.kairos.state.stats;

import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.state.stats.application.StatsBuffer;
import dev.kairos.state.stats.infrastructure.JdbcStatsWriter;
import dev.kairos.state.stats.infrastructure.StatsAggregator;
import dev.kairos.state.stats.infrastructure.StatsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Installs the after-commit observer, periodic upserts and ordered shutdown flush. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"kairos.state.settle.enabled", "kairos.state.stats.enabled"},
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(StatsProperties.class)
@EnableScheduling
public class StatsConfiguration {
    @Bean
    public StatsAggregator statsAggregator(
            CellRouter cells,
            Clock clock,
            MeterRegistry meters,
            StatsProperties properties,
            @Value("${HOSTNAME:local}") String pod) {
        return new StatsAggregator(
                StatsAggregator.nodeId(pod, clock),
                new StatsBuffer(clock, shard -> cells.forShard(shard).id(), properties.maximumBuckets(), meters),
                new JdbcStatsWriter(cells),
                meters);
    }
}
