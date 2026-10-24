package dev.kairos.api.stats;

import dev.kairos.api.stats.application.StatsQueries;
import dev.kairos.api.stats.infrastructure.JdbcStatsQueries;
import dev.kairos.persistence.cell.CellRouter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wiring for team statistics. */
@Configuration(proxyBeanMethods = false)
public class StatsConfiguration {
    /** Creates the JDBC statistics query. */
    @Bean
    public StatsQueries statsQueries(
            @Qualifier("controlJdbcClient") JdbcClient control, CellRouter cells, Clock clock) {
        return new JdbcStatsQueries(control, cells, clock);
    }
}
