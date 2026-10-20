package dev.kairos.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.security.SecurityFixtures;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ApiKeyServiceTest {

    private final String pepperV1 = SecurityFixtures.randomHexKey();
    private final String pepperV2 = SecurityFixtures.randomHexKey();
    private final ApiKeyService v1 =
            new ApiKeyService(ApiKeyPeppers.of(1, pepperV1, null, null), SecurityFixtures.RANDOM);
    private final ApiKeyService v2 =
            new ApiKeyService(ApiKeyPeppers.of(2, pepperV2, 1, pepperV1), SecurityFixtures.RANDOM);

    @Test
    void issuesKeysInTheDocumentedFormat() {
        IssuedApiKey issued = v1.issue();
        String key = issued.key().reveal();

        assertThat(key).matches("kairos_[A-Za-z0-9]{16}_[A-Za-z0-9_-]{43}");
        assertThat(key).startsWith("kairos_" + issued.keyId() + "_");
        assertThat(issued.pepperVersion()).isEqualTo(1);
        assertThat(ApiKeyService.looksLikeApiKey(key)).isTrue();
        assertThat(ApiKeyService.looksLikeApiKey("eyJhbGciOi")).isFalse();
        assertThat(v1.issue().keyId()).isNotEqualTo(issued.keyId());
    }

    @Test
    void storesHmacSha256OfTheSecretUnderThePepper() throws Exception {
        IssuedApiKey issued = v1.issue();
        String secret = issued.key().reveal().substring(24);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HexFormat.of().parseHex(pepperV1), "HmacSHA256"));
        assertThat(issued.secretHash()).isEqualTo(mac.doFinal(secret.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void verifiesThePresentedKey() {
        IssuedApiKey issued = v1.issue();
        PresentedApiKey presented = v1.parse(issued.key().reveal()).orElseThrow();

        assertThat(presented.keyId()).isEqualTo(issued.keyId());
        assertThat(v1.verify(presented, issued.secretHash(), 1)).isEqualTo(ApiKeyMatch.MATCH);
        assertThat(v1.verify(presented, issued.secretHash(), 1).matches()).isTrue();
        PresentedApiKey wrong = v1.parse(v1.issue().key().reveal()).orElseThrow();
        assertThat(v1.verify(wrong, issued.secretHash(), 1)).isEqualTo(ApiKeyMatch.NO_MATCH);
        assertThat(v1.verify(wrong, issued.secretHash(), 1).matches()).isFalse();
    }

    @Test
    void keepsWorkingAcrossAPepperRotation() {
        IssuedApiKey old = v1.issue();
        PresentedApiKey presented = v2.parse(old.key().reveal()).orElseThrow();

        assertThat(v2.verify(presented, old.secretHash(), 1)).isEqualTo(ApiKeyMatch.MATCH_OUTDATED_PEPPER);
        byte[] rehashed = v2.hash(presented);
        assertThat(v2.verify(presented, rehashed, v2.currentPepperVersion())).isEqualTo(ApiKeyMatch.MATCH);
        assertThat(v2.issue().pepperVersion()).isEqualTo(2);
        // Once the previous pepper is removed, keys still hashed with it no longer verify.
        ApiKeyService v2Only = new ApiKeyService(ApiKeyPeppers.of(2, pepperV2, null, null), SecurityFixtures.RANDOM);
        assertThat(v2Only.verify(presented, old.secretHash(), 1)).isEqualTo(ApiKeyMatch.NO_MATCH);
    }

    @Test
    void parsesOnlyWellFormedKeys() {
        String valid = v1.issue().key().reveal();

        assertThat(v1.parse(valid)).isPresent();
        for (String bad : new String[] {
            "",
            valid + "x",
            valid.substring(1),
            "sk_prod_" + valid.substring(7, valid.length() - 1),
            valid.substring(0, 23) + "-" + valid.substring(24),
            valid.substring(0, 10) + "!" + valid.substring(11),
            valid.substring(0, 30) + "+" + valid.substring(31)
        }) {
            assertThat(v1.parse(bad)).as("rejects a malformed key").isEqualTo(Optional.empty());
        }
    }

    @Test
    void peppersAreValidatedAndNeverPrinted() {
        assertThatThrownBy(() -> ApiKeyPeppers.of(1, "abc", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiKeyPeppers.of(1, "g".repeat(64), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain("gggg");
        assertThatThrownBy(() -> ApiKeyPeppers.of(0, pepperV1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiKeyPeppers.of(1, pepperV1, 1, pepperV2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiKeyPeppers.of(1, pepperV1, 2, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(v2.toString())
                .doesNotContain(pepperV1)
                .doesNotContain(pepperV2)
                .contains("current=2");
    }

    @Test
    void issuedKeysNeverPrintTheirSecret() {
        IssuedApiKey issued = v1.issue();
        PresentedApiKey presented = v1.parse(issued.key().reveal()).orElseThrow();
        String secret = issued.key().reveal().substring(24);

        assertThat(issued.toString()).contains(issued.keyId()).doesNotContain(secret);
        assertThat(presented.toString()).contains(issued.keyId()).doesNotContain(secret);
        assertThat(issued)
                .isEqualTo(new IssuedApiKey(issued.keyId(), issued.key(), issued.secretHash(), 1))
                .hasSameHashCodeAs(new IssuedApiKey(issued.keyId(), issued.key(), issued.secretHash(), 1));
        assertThat(issued).isNotEqualTo(v1.issue());
    }
}
