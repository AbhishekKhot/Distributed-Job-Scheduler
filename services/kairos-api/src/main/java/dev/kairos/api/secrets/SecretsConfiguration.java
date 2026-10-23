package dev.kairos.api.secrets;

import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.secrets.application.SecretSealer;
import dev.kairos.api.secrets.application.SecretStore;
import dev.kairos.api.secrets.application.SecretsUseCase;
import dev.kairos.api.secrets.infrastructure.JdbcSecretStore;
import dev.kairos.api.secrets.infrastructure.KeysetSecretSealer;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.cipher.SecretCipher;
import dev.kairos.security.keyset.KeysetService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Wires credential storage and row-bound encryption from the existing security library. */
@Configuration(proxyBeanMethods = false)
public class SecretsConfiguration {
    @Bean
    public SecretStore secretStore(
            @Qualifier("controlJdbcClient") JdbcClient control, ControlTransactions transactions, AuditWriter audit) {
        return new JdbcSecretStore(control, transactions, audit);
    }

    @Bean
    public SecretSealer secretSealer(
            SecretStore store, KeysetService keys, SecretCipher cipher, JsonMapper json, Clock clock) {
        return new KeysetSecretSealer(store, keys, cipher, json, clock);
    }

    @Bean
    public SecretsUseCase secretsUseCase(SecretStore store, SecretSealer sealer, Clock clock) {
        return new SecretsUseCase(store, sealer, clock);
    }
}
