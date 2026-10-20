package dev.kairos.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretValueTest {

    @Test
    void revealsTheValueButNeverPrintsIt() {
        SecretValue secret = SecretValue.of("kairos_super_secret");

        assertThat(secret.reveal()).isEqualTo("kairos_super_secret");
        assertThat(secret.toString()).doesNotContain("super").contains("redacted");
        assertThat(String.valueOf(secret)).doesNotContain("secret_");
    }

    @Test
    void comparesByValue() {
        assertThat(SecretValue.of("a")).isEqualTo(SecretValue.of("a")).isNotEqualTo(SecretValue.of("b"));
        assertThat(SecretValue.of("a")).hasSameHashCodeAs(SecretValue.of("b"));
        assertThat((Object) SecretValue.of("a")).isNotEqualTo("a");
    }

    @Test
    void rejectsEmptySecrets() {
        assertThatThrownBy(() -> SecretValue.of("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void randomTokensHaveTheRequestedShape() {
        String alphanumeric = RandomTokens.alphanumeric(SecurityFixtures.RANDOM, 64);
        String base64 = RandomTokens.base64Url(SecurityFixtures.RANDOM, 32);

        assertThat(alphanumeric).hasSize(64).matches("[A-Za-z0-9]+");
        assertThat(base64).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(RandomTokens.base64Url(SecurityFixtures.RANDOM, 32)).isNotEqualTo(base64);
        assertThat(RandomTokens.isBase64Url('-')).isTrue();
        assertThat(RandomTokens.isBase64Url('+')).isFalse();
        assertThat(RandomTokens.isAlphanumeric('_')).isFalse();
        assertThatThrownBy(() -> RandomTokens.bytes(SecurityFixtures.RANDOM, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RandomTokens.alphanumeric(SecurityFixtures.RANDOM, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
