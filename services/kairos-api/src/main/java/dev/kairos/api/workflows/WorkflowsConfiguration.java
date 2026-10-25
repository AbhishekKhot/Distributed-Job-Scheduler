package dev.kairos.api.workflows;

import dev.kairos.api.team.TeamProperties;
import dev.kairos.api.team.application.TeamStore;
import dev.kairos.api.workflows.application.WorkflowEndpointCatalog;
import dev.kairos.api.workflows.application.WorkflowStore;
import dev.kairos.api.workflows.application.WorkflowsUseCase;
import dev.kairos.api.workflows.infrastructure.JdbcWorkflowEndpointCatalog;
import dev.kairos.api.workflows.infrastructure.JdbcWorkflowStore;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.engine.run.RunCreator;
import dev.kairos.engine.run.RunFactory;
import dev.kairos.persistence.cell.CellRouter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Wiring for workflow APIs 46–58. */
@Configuration(proxyBeanMethods = false)
public class WorkflowsConfiguration {
    @Bean
    public WorkflowEndpointCatalog workflowEndpointCatalog(
            @Qualifier("controlJdbcClient") JdbcClient control, JsonMapper json) {
        return new JdbcWorkflowEndpointCatalog(control, json);
    }

    @Bean
    public WorkflowStore workflowStore(
            CellRouter cells,
            WorkflowEndpointCatalog endpoints,
            KairosIdFactory ids,
            RunFactory factory,
            RunCreator creator,
            OutboxWriter outbox,
            JsonMapper json) {
        return new JdbcWorkflowStore(cells, endpoints, ids, factory, creator, outbox, json);
    }

    @Bean
    public WorkflowsUseCase workflowsUseCase(
            WorkflowStore store,
            WorkflowEndpointCatalog endpoints,
            TeamStore teams,
            TeamProperties properties,
            KairosIdFactory ids,
            Clock clock,
            JsonMapper json) {
        return new WorkflowsUseCase(store, endpoints, teams, properties, ids, clock, json);
    }
}
