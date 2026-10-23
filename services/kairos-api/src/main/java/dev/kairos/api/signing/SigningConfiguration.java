package dev.kairos.api.signing;

import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.signing.application.SigningStore;
import dev.kairos.api.signing.application.SigningUseCase;
import dev.kairos.api.signing.infrastructure.JdbcSigningStore;
import dev.kairos.api.signing.infrastructure.SigningKeyPruner;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.keyset.KeysetService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Signing-key lifecycle wiring and durable overlap cleanup. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SigningProperties.class)
@EnableScheduling
public class SigningConfiguration {
    @Bean
    public SigningStore signingStore(
            @Qualifier("controlJdbcClient") JdbcClient control, ControlTransactions transactions, AuditWriter writer) {
        return new JdbcSigningStore(control, transactions, writer);
    }

    @Bean
    public SigningUseCase signingUseCase(
            SigningStore store, KeysetService keys, Clock clock, SigningProperties properties) {
        return new SigningUseCase(store, keys, clock, properties.overlap());
    }

    @Bean
    public SigningKeyPruner signingKeyPruner(SigningUseCase keys, SigningProperties properties) {
        return new SigningKeyPruner(keys, properties.pruneBatchSize());
    }
}
