package dev.kairos.api.audit;

import dev.kairos.api.audit.application.AuditQueryUseCase;
import dev.kairos.api.audit.application.AuditStore;
import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.audit.infrastructure.JdbcAuditStore;
import dev.kairos.api.audit.infrastructure.JdbcAuditWriter;
import dev.kairos.domain.id.KairosIdFactory;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Shared audit writer and API 24 query wiring. */
@Configuration(proxyBeanMethods = false)
public class AuditConfiguration {
    @Bean
    public AuditWriter auditWriter(
            @Qualifier("controlJdbcClient") JdbcClient control, KairosIdFactory ids, Clock clock, JsonMapper json) {
        return new JdbcAuditWriter(control, ids, clock, json);
    }

    @Bean
    public AuditStore auditStore(@Qualifier("controlJdbcClient") JdbcClient control, JsonMapper json) {
        return new JdbcAuditStore(control, json);
    }

    @Bean
    public AuditQueryUseCase auditQueryUseCase(AuditStore store, Clock clock) {
        return new AuditQueryUseCase(store, clock);
    }
}
