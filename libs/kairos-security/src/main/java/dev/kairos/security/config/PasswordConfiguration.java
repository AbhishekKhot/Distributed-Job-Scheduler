package dev.kairos.security.config;

import dev.kairos.security.password.PasswordHasher;
import dev.kairos.security.password.PasswordPolicy;
import java.security.SecureRandom;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Password hashing and policy. Imported by the API and the maintenance job ({@code user reset-password}).
 *
 * <p>Uses the service's {@link SecureRandom} bean if it has one, otherwise a new one.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class PasswordConfiguration {

    /** Creates the configuration; Spring calls it. */
    public PasswordConfiguration() {}

    /**
     * The Argon2id hasher.
     *
     * @param properties the security configuration
     * @param random the service's CSPRNG, if any
     * @return the hasher
     */
    @Bean
    public PasswordHasher passwordHasher(SecurityProperties properties, ObjectProvider<SecureRandom> random) {
        return new PasswordHasher(properties.password().toParameters(), random.getIfAvailable(SecureRandom::new));
    }

    /**
     * The password rules with the bundled common-password list.
     *
     * @return the policy
     */
    @Bean
    public PasswordPolicy passwordPolicy() {
        return PasswordPolicy.withBundledList();
    }
}
