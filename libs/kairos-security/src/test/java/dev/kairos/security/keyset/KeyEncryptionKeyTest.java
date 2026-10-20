package dev.kairos.security.keyset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.security.SecurityFixtures;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class KeyEncryptionKeyTest {

    private static final String KMS_URI =
            "gcp-kms://projects/kairos-prod/locations/europe-west1/keyRings/kairos/cryptoKeys/kek";

    @Test
    void theLocalKeyRefusesToLoadOutsideTheLocalProfile() {
        String key = SecurityFixtures.randomHexKey();

        assertThatThrownBy(() -> LocalKeyEncryptionKey.forLocalProfile(key, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local");
    }

    @Test
    void theLocalKeyNeeds32HexBytesAndNeverEchoesThem() {
        assertThatThrownBy(() -> LocalKeyEncryptionKey.forLocalProfile("abcd", true))
                .isInstanceOf(IllegalArgumentException.class);
        String notHex = "z".repeat(64);
        assertThatThrownBy(() -> LocalKeyEncryptionKey.forLocalProfile(notHex, true))
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain("zzzz");
    }

    @Test
    void theLocalKeyEncryptsDeterministicallyForTheSameKey() throws Exception {
        String hex = SecurityFixtures.randomHexKey();
        byte[] ad = "ad".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext =
                LocalKeyEncryptionKey.forLocalProfile(hex, true).aead().encrypt(new byte[] {4}, ad);

        assertThat(LocalKeyEncryptionKey.forLocalProfile(hex, true).aead().decrypt(ciphertext, ad))
                .containsExactly(4);
    }

    @Test
    void acceptsOnlyCloudKmsCryptoKeyUris() {
        assertThat(GcpKmsKeyEncryptionKey.requireValidUri(KMS_URI)).isEqualTo(KMS_URI);
        for (String bad : new String[] {
            "aws-kms://arn:aws:kms:eu-west-1:1:key/x",
            "gcp-kms://projects/p/locations/l/keyRings/r",
            "gcp-kms://projects/p/locations/l/keyRings/r/cryptoKeys/k/cryptoKeyVersions/1",
            "gcp-kms://projects/p/locations/l/keyRings/r/secrets/k",
            "gcp-kms://projects/p q/locations/l/keyRings/r/cryptoKeys/k",
            "gcp-kms://projects//locations/l/keyRings/r/cryptoKeys/k",
            "gcp-kms://projects/" + "p".repeat(64) + "/locations/l/keyRings/r/cryptoKeys/k",
            "gcp-kms://" + "a".repeat(600)
        }) {
            assertThatThrownBy(() -> GcpKmsKeyEncryptionKey.requireValidUri(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> GcpKmsKeyEncryptionKey.connect("local-kek://kairos"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCloudKmsKeyExposesItsUriAndAead() {
        LocalKeyEncryptionKey stand = SecurityFixtures.localKek();
        GcpKmsKeyEncryptionKey kms = new GcpKmsKeyEncryptionKey(KMS_URI, stand.aead());

        assertThat(kms.uri()).isEqualTo(KMS_URI);
        assertThat(kms.aead()).isSameAs(stand.aead());
        assertThat(kms.toString()).contains(KMS_URI);
    }
}
