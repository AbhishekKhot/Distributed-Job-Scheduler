package dev.kairos.watcher.singleton;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.config.PersistenceConfiguration;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.persistence.tx.ControlTransactions;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Spring wiring for singleton watcher maintenance tasks. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kairos.watcher.enabled", havingValue = "true")
@Import(PersistenceConfiguration.class)
public class SingletonConfiguration {
    /** Creates this configuration; Spring calls it. */
    public SingletonConfiguration() {}

    /** Opens and holds the dedicated advisory-lock connection to the control database. */
    @Bean(destroyMethod = "close")
    public SingletonCoordinator singletonCoordinator(DataSourceRegistry registry) {
        return new SingletonCoordinator(registry.control().dataSource());
    }

    /** Creates the endpoint auto-suspend evaluator. */
    @Bean
    public EndpointAutoSuspendEvaluator endpointAutoSuspendEvaluator(
            CellRouter cells,
            @Qualifier("controlJdbcClient") JdbcClient control,
            ControlTransactions transactions,
            KairosIdFactory ids,
            Clock watcherClock,
            MeterRegistry meters) {
        return new EndpointAutoSuspendEvaluator(cells, control, transactions, ids, watcherClock, meters);
    }

    /** Creates the review-timeout sweeper. */
    @Bean
    public ReviewTimeoutSweeper reviewTimeoutSweeper(CellRouter cells, JsonMapper json, Clock watcherClock) {
        return new ReviewTimeoutSweeper(cells, json, watcherClock);
    }

    /** Creates the retention purger for control and cell databases. */
    @Bean
    public RetentionPurger retentionPurger(
            CellRouter cells,
            @Qualifier("controlJdbcClient") JdbcClient control,
            Clock watcherClock,
            MeterRegistry meters) {
        return new RetentionPurger(cells, control, watcherClock, meters);
    }

    /** Starts scheduled tasks after they acquire the shared PostgreSQL advisory lock. */
    @Bean
    public SingletonTaskScheduler singletonTaskScheduler(
            SingletonCoordinator coordinator,
            EndpointAutoSuspendEvaluator autoSuspend,
            ReviewTimeoutSweeper reviewTimeout,
            RetentionPurger purger) {
        return new SingletonTaskScheduler(coordinator, autoSuspend, reviewTimeout, purger);
    }
}
