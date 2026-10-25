package dev.kairos.state.workflow;

import dev.kairos.engine.outbox.OutboxConfiguration;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.security.cipher.SecretCipher;
import dev.kairos.security.config.KeyManagementConfiguration;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.state.workflow.infrastructure.JdbcWorkflowProgression;
import dev.kairos.state.workflow.infrastructure.WorkflowOutputs;
import dev.kairos.state.workflow.infrastructure.WorkflowReconciler;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/** Registers transactional workflow progression and bounded recovery reconciliation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"kairos.state.settle.enabled", "kairos.state.workflow.enabled"},
        havingValue = "true",
        matchIfMissing = true)
@Import({OutboxConfiguration.class, KeyManagementConfiguration.class})
@EnableScheduling
public class WorkflowConfiguration {
    @Bean
    public JdbcWorkflowProgression workflowProgression(
            CellRouter cells,
            @Qualifier("controlJdbcClient") JdbcClient control,
            OutboxWriter outbox,
            KeysetService keysets,
            SecretCipher cipher,
            JsonMapper json,
            Clock clock) {
        return new JdbcWorkflowProgression(
                cells, control, outbox, new WorkflowOutputs(control, json, keysets, cipher), json, clock);
    }

    @Bean
    public WorkflowReconciler workflowReconciler(CellRouter cells, JdbcWorkflowProgression progression) {
        return new WorkflowReconciler(cells, progression);
    }
}
