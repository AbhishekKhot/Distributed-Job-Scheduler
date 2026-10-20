package dev.kairos.security.config;

import dev.kairos.security.cipher.SecretCipher;
import dev.kairos.security.keyset.GcpKmsKeyEncryptionKey;
import dev.kairos.security.keyset.KeyEncryptionKey;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.LocalKeyEncryptionKey;
import dev.kairos.security.signing.RequestSigner;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Keysets, secret encryption and request signing. Imported by the API, the executor and the migrations job.
 *
 * <p>The key encryption key depends on the profile: under {@value LocalKeyEncryptionKey#PROFILE} it is
 * {@code kairos.security.kek.local-key}; under any other profile it is the Cloud KMS key
 * {@code kairos.security.kek.uri}, and a configured local key is refused so production cannot fall back to it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
@Import({KeyManagementConfiguration.LocalKek.class, KeyManagementConfiguration.CloudKmsKek.class})
public class KeyManagementConfiguration {

    /** Creates the configuration; Spring calls it. */
    public KeyManagementConfiguration() {}

    /**
     * The keyset service.
     *
     * @param kek the key encryption key
     * @param properties the security configuration
     * @param random the service's CSPRNG, if any
     * @return the service
     */
    @Bean
    public KeysetService keysetService(
            KeyEncryptionKey kek, SecurityProperties properties, ObjectProvider<SecureRandom> random) {
        return new KeysetService(
                kek,
                random.getIfAvailable(SecureRandom::new),
                properties.keysetCache().expireAfterAccess(),
                properties.keysetCache().maximumSize());
    }

    /**
     * The secret and output cipher.
     *
     * @return the cipher
     */
    @Bean
    public SecretCipher secretCipher() {
        return new SecretCipher();
    }

    /**
     * The request signer.
     *
     * @param clock the service's clock, if any
     * @return the signer
     */
    @Bean
    public RequestSigner requestSigner(ObjectProvider<Clock> clock) {
        return new RequestSigner(clock.getIfAvailable(Clock::systemUTC));
    }

    /** The local key encryption key, under the {@code local} profile only. */
    @Configuration(proxyBeanMethods = false)
    @Profile(LocalKeyEncryptionKey.PROFILE)
    public static class LocalKek {

        /** Creates the configuration; Spring calls it. */
        public LocalKek() {}

        /**
         * The local key.
         *
         * @param properties the security configuration
         * @param environment the environment, to check the active profile again
         * @return the key
         * @throws IllegalStateException if {@code kairos.security.kek.local-key} is missing
         */
        @Bean
        public KeyEncryptionKey keyEncryptionKey(SecurityProperties properties, Environment environment) {
            String localKey = properties.kek().localKey();
            if (localKey == null) {
                throw new IllegalStateException("kairos.security.kek.local-key is required under the local profile");
            }
            return LocalKeyEncryptionKey.forLocalProfile(
                    localKey, environment.matchesProfiles(LocalKeyEncryptionKey.PROFILE));
        }
    }

    /** The Cloud KMS key encryption key, under every profile except {@code local}. */
    @Configuration(proxyBeanMethods = false)
    @Profile("!" + LocalKeyEncryptionKey.PROFILE)
    public static class CloudKmsKek {

        /** Creates the configuration; Spring calls it. */
        public CloudKmsKek() {}

        /**
         * The Cloud KMS key.
         *
         * @param properties the security configuration
         * @return the key
         * @throws IllegalStateException if a local key is configured, or no KMS URI is
         */
        @Bean
        public KeyEncryptionKey keyEncryptionKey(SecurityProperties properties) {
            return cloudKms(properties.kek());
        }

        static KeyEncryptionKey cloudKms(SecurityProperties.Kek kek) {
            if (kek.localKey() != null) {
                throw new IllegalStateException(
                        "kairos.security.kek.local-key is only allowed under the local profile");
            }
            String uri = kek.uri();
            if (uri == null) {
                throw new IllegalStateException("kairos.security.kek.uri is required outside the local profile");
            }
            return GcpKmsKeyEncryptionKey.connect(uri);
        }
    }
}
