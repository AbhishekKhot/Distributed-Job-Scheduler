package dev.kairos.api.jobs;

import dev.kairos.api.jobs.application.JobCreationGuard;
import dev.kairos.api.jobs.application.JobEndpointCatalog;
import dev.kairos.api.jobs.application.JobStore;
import dev.kairos.api.jobs.application.JobsUseCase;
import dev.kairos.api.jobs.infrastructure.JdbcJobCreationGuard;
import dev.kairos.api.jobs.infrastructure.JdbcJobEndpointCatalog;
import dev.kairos.api.jobs.infrastructure.JdbcJobStore;
import dev.kairos.api.team.TeamProperties;
import dev.kairos.api.team.application.TeamStore;
import dev.kairos.api.team.application.TeamUsageReader;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.engine.run.RunCreationConfiguration;
import dev.kairos.engine.run.RunCreator;
import dev.kairos.engine.run.RunFactory;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.jdbc.DataSourceRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Wiring for job APIs 34–45. */
@Configuration(proxyBeanMethods = false)
@Import(RunCreationConfiguration.class)
public class JobsConfiguration {
    @Bean
    public JobEndpointCatalog jobEndpointCatalog(
            @org.springframework.beans.factory.annotation.Qualifier("controlJdbcClient") JdbcClient control,
            JsonMapper json) {
        return new JdbcJobEndpointCatalog(control, json);
    }

    @Bean
    public JobStore jobStore(
            CellRouter cells,
            RunFactory factory,
            RunCreator creator,
            OutboxWriter outbox,
            Clock clock,
            JsonMapper json) {
        return new JdbcJobStore(cells, factory, creator, outbox, clock, json);
    }

    @Bean
    public JobCreationGuard jobCreationGuard(DataSourceRegistry databases) {
        return new JdbcJobCreationGuard(databases.control().dataSource());
    }

    @Bean
    public JobsUseCase jobsUseCase(
            JobStore store,
            JobCreationGuard creationGuard,
            JobEndpointCatalog endpoints,
            TeamStore teams,
            TeamUsageReader usage,
            TeamProperties teamProperties,
            dev.kairos.domain.id.KairosIdFactory ids,
            Clock clock,
            JsonMapper json) {
        return new JobsUseCase(store, creationGuard, endpoints, teams, usage, teamProperties, ids, clock, json);
    }
}
