package dev.kairos.persistence.config;

import dev.kairos.persistence.cell.CellMapSource;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.fanout.FanOutQuery;
import dev.kairos.persistence.health.DatabaseHealthIndicator;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import dev.kairos.persistence.tx.CellTransactions;
import dev.kairos.persistence.tx.ControlTransactions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Spring wiring of the persistence library. Services import it explicitly with
 * {@code @Import(PersistenceConfiguration.class)}; it is not an auto-configuration, so nothing connects to a database
 * unless a service asks for it.
 *
 * <p>Beans: the {@link DataSourceRegistry} (one pool per database, closed on shutdown), the {@link CellRouter}, the
 * {@link CellTransactions} and {@link ControlTransactions} entry points, the control database's {@link JdbcClient}
 * (named {@code controlJdbcClient}; cells' clients are reached through the router), the {@link FanOutQuery} helper and
 * the {@value DatabaseHealthIndicator#CONTRIBUTOR_NAME} health contributor. The data sources are deliberately not
 * beans: no code can pick a database by type, and Spring Boot's single-data-source auto-configuration stays out.
 *
 * <p>A {@link Clock} bean and a {@link MeterRegistry} are used when the service defines them; otherwise the UTC system
 * clock and a private registry are used.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersistenceProperties.class)
public class PersistenceConfiguration {

    /** Creates the configuration; Spring calls it. */
    public PersistenceConfiguration() {}

    /**
     * Opens one pool per configured database.
     *
     * @param properties the database configuration
     * @param meterRegistry the service's meter registry, if any
     * @return the registry
     */
    @Bean(destroyMethod = "close")
    public DataSourceRegistry dataSourceRegistry(
            PersistenceProperties properties, ObjectProvider<MeterRegistry> meterRegistry) {
        return DataSourceRegistry.connect(
                properties.controlSettings(),
                properties.cellSettings(),
                properties.databaseOptions(),
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * Routes shards to cells from {@code cell_map}.
     *
     * @param registry the service's databases
     * @param properties the database configuration
     * @param clock the service's clock, if any
     * @return the router
     */
    @Bean
    public CellRouter cellRouter(
            DataSourceRegistry registry, PersistenceProperties properties, ObjectProvider<Clock> clock) {
        return new CellRouter(
                CellMapSource.fromControl(registry.control()),
                registry.cells(),
                properties.cellMapRefresh(),
                clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * Runs transactions on the cell of a shard.
     *
     * @param router the cell router
     * @return the cell transactions
     */
    @Bean
    public CellTransactions cellTransactions(CellRouter router) {
        return new CellTransactions(router);
    }

    /**
     * Runs transactions on the control database.
     *
     * @param registry the service's databases
     * @return the control transactions
     */
    @Bean
    public ControlTransactions controlTransactions(DataSourceRegistry registry) {
        return new ControlTransactions(registry.control());
    }

    /**
     * The client of the control database.
     *
     * @param registry the service's databases
     * @return the client
     */
    @Bean
    public JdbcClient controlJdbcClient(DataSourceRegistry registry) {
        return registry.control().jdbc();
    }

    /**
     * Reads team-scoped lists across every cell.
     *
     * @param router the cell router
     * @param properties the database configuration
     * @return the helper
     */
    @Bean
    public FanOutQuery fanOutQuery(CellRouter router, PersistenceProperties properties) {
        return new FanOutQuery(router, properties.fanOutTimeout());
    }

    /**
     * One readiness check per database, grouped under {@value DatabaseHealthIndicator#CONTRIBUTOR_NAME}.
     *
     * @param registry the service's databases
     * @param properties the database configuration
     * @return the composite health contributor
     */
    @Bean(name = DatabaseHealthIndicator.CONTRIBUTOR_NAME)
    public CompositeHealthContributor kairosDatabases(DataSourceRegistry registry, PersistenceProperties properties) {
        return DatabaseHealthIndicator.forRegistry(registry, properties.healthTimeout());
    }
}
