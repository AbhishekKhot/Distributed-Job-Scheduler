package dev.kairos.api.cabundles;

import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.cabundles.application.CaBundleStore;
import dev.kairos.api.cabundles.application.CaBundlesUseCase;
import dev.kairos.api.cabundles.infrastructure.JdbcCaBundleStore;
import dev.kairos.persistence.tx.ControlTransactions;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wires team certificate-bundle APIs and the shared audit writer. */
@Configuration(proxyBeanMethods = false)
public class CaBundlesConfiguration {
    @Bean
    public CaBundleStore caBundleStore(
            @Qualifier("controlJdbcClient") JdbcClient control, ControlTransactions transactions, AuditWriter audit) {
        return new JdbcCaBundleStore(control, transactions, audit);
    }

    @Bean
    public CaBundlesUseCase caBundlesUseCase(CaBundleStore store, Clock clock) {
        return new CaBundlesUseCase(store, clock);
    }
}
