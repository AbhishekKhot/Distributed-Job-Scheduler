package dev.kairos.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.security.SecurityFixtures;
import dev.kairos.security.apikey.ApiKeyService;
import dev.kairos.security.cipher.SecretCipher;
import dev.kairos.security.keyset.KeyEncryptionKey;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.LocalKeyEncryptionKey;
import dev.kairos.security.password.PasswordHasher;
import dev.kairos.security.password.PasswordPolicy;
import dev.kairos.security.signing.RequestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SecurityConfigurationTest {

    private final String localKey = SecurityFixtures.randomHexKey();
    private final String pepper = SecurityFixtures.randomHexKey();

    private ApplicationContextRunner local() {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(LocalKeyEncryptionKey.PROFILE));
    }

    @Test
    void wiresEverythingUnderTheLocalProfile() {
        local().withUserConfiguration(
                        PasswordConfiguration.class, ApiKeyConfiguration.class, KeyManagementConfiguration.class)
                .withPropertyValues(
                        "kairos.security.kek.local-key=" + localKey, "kairos.security.api-key.pepper=" + pepper)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(KeyEncryptionKey.class)).isInstanceOf(LocalKeyEncryptionKey.class);
                    assertThat(context).hasSingleBean(KeysetService.class);
                    assertThat(context).hasSingleBean(SecretCipher.class);
                    assertThat(context).hasSingleBean(RequestSigner.class);
                    assertThat(context).hasSingleBean(PasswordPolicy.class);
                    assertThat(context.getBean(PasswordHasher.class).hash("a-strong-password-1"))
                            .startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
                    assertThat(context.getBean(ApiKeyService.class).currentPepperVersion())
                            .isEqualTo(1);
                    SecurityProperties properties = context.getBean(SecurityProperties.class);
                    assertThat(properties.accessToken().ttl()).hasMinutes(15);
                    assertThat(properties.accessToken().clockSkew()).hasSeconds(30);
                    assertThat(properties.refreshToken().ttl()).hasDays(30);
                    assertThat(properties.toString()).doesNotContain(localKey).doesNotContain(pepper);
                });
    }

    @Test
    void theLocalProfileNeedsTheLocalKey() {
        local().withUserConfiguration(KeyManagementConfiguration.class)
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("kairos.security.kek.local-key is required"));
    }

    @Test
    void productionRefusesTheLocalKey() {
        new ApplicationContextRunner()
                .withUserConfiguration(KeyManagementConfiguration.class)
                .withPropertyValues("kairos.security.kek.local-key=" + localKey)
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("only allowed under the local profile"));
    }

    @Test
    void productionNeedsAKmsUri() {
        new ApplicationContextRunner()
                .withUserConfiguration(KeyManagementConfiguration.class)
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("kairos.security.kek.uri is required"));
        new ApplicationContextRunner()
                .withUserConfiguration(KeyManagementConfiguration.class)
                .withPropertyValues("kairos.security.kek.uri=https://example.com/key")
                .run(context -> assertThat(context).getFailure().rootCause().hasMessageContaining("gcp-kms://"));
    }

    @Test
    void theApiKeyServiceNeedsAPepper() {
        new ApplicationContextRunner()
                .withUserConfiguration(ApiKeyConfiguration.class)
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("kairos.security.api-key.pepper is required"));
    }

    @Test
    void rejectsMalformedSecretsAndWeakerPasswordCosts() {
        local().withUserConfiguration(PasswordConfiguration.class, ApiKeyConfiguration.class)
                .withPropertyValues("kairos.security.api-key.pepper=short", "kairos.security.password.memory-kib=1024")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .message()
                            .doesNotContain("short\"");
                });
    }

    @Test
    void supportsAPreviousPepperDuringRotation() {
        new ApplicationContextRunner()
                .withUserConfiguration(ApiKeyConfiguration.class)
                .withPropertyValues(
                        "kairos.security.api-key.pepper=" + pepper,
                        "kairos.security.api-key.pepper-version=2",
                        "kairos.security.api-key.previous-pepper=" + SecurityFixtures.randomHexKey(),
                        "kairos.security.api-key.previous-pepper-version=1")
                .run(context -> {
                    assertThat(context.getBean(ApiKeyService.class).currentPepperVersion())
                            .isEqualTo(2);
                    assertThat(context.getBean(SecurityProperties.class)
                                    .apiKey()
                                    .toString())
                            .contains("previousPepperVersion=1")
                            .doesNotContain(pepper);
                });
    }
}
