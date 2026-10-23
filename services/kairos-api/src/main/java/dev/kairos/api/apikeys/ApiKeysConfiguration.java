package dev.kairos.api.apikeys;

import dev.kairos.api.apikeys.application.ApiKeyStore;
import dev.kairos.api.apikeys.application.ApiKeyUsage;
import dev.kairos.api.apikeys.application.ApiKeysUseCase;
import dev.kairos.api.apikeys.infrastructure.JdbcApiKeyStore;
import dev.kairos.api.apikeys.infrastructure.ThrottledApiKeyUsage;
import dev.kairos.api.apikeys.web.ApiKeyUsageInterceptor;
import dev.kairos.api.config.ApiProperties;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.apikey.ApiKeyService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Wiring for API key management and throttled usage tracking (T-203b). */
@Configuration(proxyBeanMethods = false)
public class ApiKeysConfiguration {

    @Bean
    public ApiKeyStore apiKeyStore(
            @Qualifier("controlJdbcClient") JdbcClient control, ControlTransactions transactions, KairosIdFactory ids) {
        return new JdbcApiKeyStore(control, transactions, ids);
    }

    @Bean
    public ApiKeysUseCase apiKeysUseCase(ApiKeyStore store, ApiKeyService keys, Clock clock) {
        return new ApiKeysUseCase(store, keys, clock);
    }

    @Bean
    public ApiKeyUsage apiKeyUsage(ApiKeyStore store, Clock clock, ApiProperties properties) {
        return new ThrottledApiKeyUsage(store, clock, properties.auth().cacheMaximumSize());
    }

    @Bean
    public WebMvcConfigurer apiKeyUsageWebConfigurer(ApiKeyUsage usage) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new ApiKeyUsageInterceptor(usage)).order(-10);
            }
        };
    }
}
