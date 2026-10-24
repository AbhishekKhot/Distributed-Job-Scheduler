package dev.kairos.api.runs;

import dev.kairos.api.runs.application.RunQueries;
import dev.kairos.api.runs.application.RunQueryUseCase;
import dev.kairos.api.runs.infrastructure.JdbcRunQueries;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.persistence.cell.CellRouter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wiring for LLD run APIs 59–61. */
@Configuration(proxyBeanMethods = false)
public class RunsConfiguration {
    /** Creates the JDBC read and cancellation adapter. */
    @Bean
    public RunQueries runQueries(
            @Qualifier("controlJdbcClient") JdbcClient control, CellRouter cells, OutboxWriter outbox, Clock clock) {
        return new JdbcRunQueries(control, cells, outbox, clock);
    }

    /** Creates the validated run use case. */
    @Bean
    public RunQueryUseCase runQueryUseCase(RunQueries queries, Clock clock) {
        return new RunQueryUseCase(queries, clock);
    }
}
