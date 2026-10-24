package dev.kairos.api.dlq;

import dev.kairos.api.dlq.application.DlqQueries;
import dev.kairos.api.dlq.application.DlqQueryUseCase;
import dev.kairos.api.dlq.infrastructure.JdbcDlqQueries;
import dev.kairos.engine.run.RunCreator;
import dev.kairos.engine.run.RunFactory;
import dev.kairos.persistence.cell.CellRouter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wiring for the DLQ query and triage APIs. */
@Configuration(proxyBeanMethods = false)
@Import(dev.kairos.engine.run.RunCreationConfiguration.class)
public class DlqConfiguration {
    /** Creates the cell-routed DLQ adapter. */
    @Bean
    public DlqQueries dlqQueries(
            @Qualifier("controlJdbcClient") JdbcClient control,
            CellRouter cells,
            Clock clock,
            RunFactory factory,
            RunCreator creator,
            tools.jackson.databind.json.JsonMapper json) {
        return new JdbcDlqQueries(control, cells, clock, factory, creator, json);
    }

    /** Creates the DLQ use case. */
    @Bean
    public DlqQueryUseCase dlqQueryUseCase(DlqQueries queries) {
        return new DlqQueryUseCase(queries);
    }
}
