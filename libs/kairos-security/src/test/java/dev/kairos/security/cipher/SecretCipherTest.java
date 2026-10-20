package dev.kairos.security.cipher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.security.SecurityFixtures;
import dev.kairos.security.keyset.KeyPurpose;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.SealedKeyset;
import dev.kairos.security.keyset.TeamAead;
import dev.kairos.testsupport.TestIds;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final byte[] SECRET = "client-secret-value".getBytes(StandardCharsets.UTF_8);

    private final KeysetService keysets = SecurityFixtures.keysetService();
    private final SecretCipher cipher = new SecretCipher();
    private final TeamId team = TestIds.teamId();
    private final SealedKeyset secretsKeyset = keysets.createTeamKeyset(team, KeyPurpose.SECRETS);
    private final TeamAead secrets = keysets.aead(team, KeyPurpose.SECRETS, secretsKeyset);
    private final TeamAead outputs =
            keysets.aead(team, KeyPurpose.OUTPUTS, keysets.createTeamKeyset(team, KeyPurpose.OUTPUTS));

    @Test
    void roundTripsASecret() {
        byte[] ciphertext = cipher.encryptSecret(secrets, "orders-api", 3, SECRET);

        assertThat(ciphertext).isNotEqualTo(SECRET);
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain("client-secret");
        assertThat(cipher.decryptSecret(secrets, "orders-api", 3, ciphertext)).isEqualTo(SECRET);
        assertThat(cipher.encryptSecret(secrets, "orders-api", 3, SECRET)).isNotEqualTo(ciphertext);
    }

    @Test
    void aCiphertextSwappedToAnotherRowDoesNotDecrypt() {
        byte[] ciphertext = cipher.encryptSecret(secrets, "orders-api", 3, SECRET);

        assertThatThrownBy(() -> cipher.decryptSecret(secrets, "billing-api", 3, ciphertext))
                .isInstanceOf(DecryptionFailedException.class)
                .hasMessage("the value cannot be decrypted");
        assertThatThrownBy(() -> cipher.decryptSecret(secrets, "orders-api", 4, ciphertext))
                .isInstanceOf(DecryptionFailedException.class);
        // The same keyset bytes presented as another team's: the keyset itself does not open, and a keyset of another
        // team cannot decrypt either.
        TeamId other = TestIds.teamId();
        TeamAead otherSecrets =
                keysets.aead(other, KeyPurpose.SECRETS, keysets.createTeamKeyset(other, KeyPurpose.SECRETS));
        assertThatThrownBy(() -> cipher.decryptSecret(otherSecrets, "orders-api", 3, ciphertext))
                .isInstanceOf(DecryptionFailedException.class);
        byte[] tampered = ciphertext.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decryptSecret(secrets, "orders-api", 3, tampered))
                .isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void sealsOutputsPerRunAndName() {
        RunId run = TestIds.runId(17);
        byte[] sealed = cipher.encryptOutput(outputs, run, "token", SECRET);

        assertThat(cipher.decryptOutput(outputs, run, "token", sealed)).isEqualTo(SECRET);
        assertThatThrownBy(() -> cipher.decryptOutput(outputs, TestIds.runId(17), "token", sealed))
                .isInstanceOf(DecryptionFailedException.class);
        assertThatThrownBy(() -> cipher.decryptOutput(outputs, run, "other", sealed))
                .isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void eachValueKindNeedsItsOwnKeyset() {
        assertThatThrownBy(() -> cipher.encryptSecret(outputs, "orders-api", 1, SECRET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.encryptOutput(secrets, TestIds.runId(1), "x", SECRET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decryptSecret(outputs, "orders-api", 1, SECRET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decryptOutput(secrets, TestIds.runId(1), "x", SECRET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capsThePlaintextSize() {
        byte[] large = new byte[SecretCipher.MAX_PLAINTEXT_BYTES + 1];

        assertThatThrownBy(() -> cipher.encryptSecret(secrets, "big", 1, large))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(cipher.encryptSecret(secrets, "big", 1, new byte[SecretCipher.MAX_PLAINTEXT_BYTES]).length)
                .isLessThanOrEqualTo(16 * 1024);
    }
}
