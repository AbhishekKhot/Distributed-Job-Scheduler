package dev.kairos.security.config;

import dev.kairos.security.apikey.ApiKeyPeppers;
import dev.kairos.security.apikey.ApiKeyService;
import java.security.SecureRandom;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API key issuing and verification. Imported by the API only; it fails at startup when
 * {@code kairos.security.api-key.pepper} is missing, so a misconfigured API never runs without its pepper.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class ApiKeyConfiguration {

    /** Creates the configuration; Spring calls it. */
    public ApiKeyConfiguration() {}

    /**
     * The API key service.
     *
     * @param properties the security configuration
     * @param random the service's CSPRNG, if any
     * @return the service
     * @throws IllegalStateException if the pepper is not configured
     */
    @Bean
    public ApiKeyService apiKeyService(SecurityProperties properties, ObjectProvider<SecureRandom> random) {
        SecurityProperties.ApiKey apiKey = properties.apiKey();
        String pepper = apiKey.pepper();
        if (pepper == null) {
            throw new IllegalStateException("kairos.security.api-key.pepper is required");
        }
        ApiKeyPeppers peppers = ApiKeyPeppers.of(
                apiKey.pepperVersion(), pepper, apiKey.previousPepperVersion(), apiKey.previousPepper());
        return new ApiKeyService(peppers, random.getIfAvailable(SecureRandom::new));
    }
}
