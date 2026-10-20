package dev.kairos.security.keyset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.crypto.tink.KeysetHandle;
import dev.kairos.domain.id.TeamId;
import dev.kairos.security.SecurityFixtures;
import dev.kairos.testsupport.TestIds;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class KeysetServiceTest {

    private final LocalKeyEncryptionKey kek = SecurityFixtures.localKek();
    private final KeysetService keysets = SecurityFixtures.keysetService(kek);
    private final TeamId team = TestIds.teamId();

    @Test
    void sealsATeamAeadKeysetThatOpensOnAnotherInstance() throws Exception {
        SealedKeyset sealed = keysets.createTeamKeyset(team, KeyPurpose.SECRETS);
        byte[] ad = "ad".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext =
                keysets.aead(team, KeyPurpose.SECRETS, sealed).aead().encrypt(new byte[] {1, 2}, ad);

        // A fresh service with the same KEK (another pod) opens the stored keyset.
        KeysetService other = SecurityFixtures.keysetService(kek);
        assertThat(other.aead(team, KeyPurpose.SECRETS, sealed).aead().decrypt(ciphertext, ad))
                .containsExactly(1, 2);
        assertThat(sealed.kekUri()).isEqualTo(LocalKeyEncryptionKey.URI).isEqualTo(keysets.kekUri());
    }

    @Test
    void aSealedKeysetOpensOnlyForItsTeamAndPurpose() {
        SealedKeyset sealed = keysets.createTeamKeyset(team, KeyPurpose.SECRETS);

        assertThatThrownBy(() -> keysets.aead(TestIds.teamId(), KeyPurpose.SECRETS, sealed))
                .isInstanceOf(KeysetException.class);
        assertThatThrownBy(() -> keysets.aead(team, KeyPurpose.OUTPUTS, sealed)).isInstanceOf(KeysetException.class);
    }

    @Test
    void refusesKeysetsSealedByAnotherKek() {
        SealedKeyset sealed = keysets.createTeamKeyset(team, KeyPurpose.OUTPUTS);
        SealedKeyset relabelled =
                new SealedKeyset(sealed.ciphertext(), "gcp-kms://projects/p/locations/l/keyRings/r/cryptoKeys/k");

        assertThatThrownBy(() -> keysets.aead(team, KeyPurpose.OUTPUTS, relabelled))
                .isInstanceOf(KeysetException.class)
                .hasMessageContaining("another key encryption key");
        // Same URI, different key material: the ciphertext does not open.
        assertThatThrownBy(() -> SecurityFixtures.keysetService().aead(team, KeyPurpose.OUTPUTS, sealed))
                .isInstanceOf(KeysetException.class);
    }

    @Test
    void signingKeysAreOnlyCreatedAsSigningKeys() {
        assertThatThrownBy(() -> keysets.createTeamKeyset(team, KeyPurpose.SIGNING))
                .isInstanceOf(IllegalArgumentException.class);
        SealedKeyset signing = keysets.createSigningKey(team).sealed();
        assertThatThrownBy(() -> keysets.aead(team, KeyPurpose.SIGNING, signing))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theExportedSigningKeyComputesTheSameHmac() throws Exception {
        NewSigningKey created = keysets.createSigningKey(team);
        String exported = created.exportedKey().reveal();

        assertThat(created.version()).isEqualTo(1);
        assertThat(exported).startsWith("ksig_v1_").hasSize("ksig_v1_".length() + 43);
        TeamSigningKey key = keysets.signingKey(team, created.sealed());
        assertThat(key.version()).isEqualTo(1);

        byte[] data = "message".getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getUrlDecoder().decode(exported.substring(8)), "HmacSHA256"));
        assertThat(key.mac().computeMac(data)).isEqualTo(mac.doFinal(data));
    }

    @Test
    void rotationPromotesANewVersionAndKeepsOnlyThePreviousOne() throws Exception {
        NewSigningKey v1 = keysets.createSigningKey(team);
        NewSigningKey v2 = keysets.rotateSigningKey(team, v1.sealed());
        NewSigningKey v3 = keysets.rotateSigningKey(team, v2.sealed());

        assertThat(v2.version()).isEqualTo(2);
        assertThat(v3.version()).isEqualTo(3);
        assertThat(v3.exportedKey().reveal()).startsWith("ksig_v3_");
        assertThat(keysets.signingKey(team, v3.sealed()).version()).isEqualTo(3);
        byte[] data = {7};
        byte[] tagV2 = keysets.signingKey(team, v2.sealed()).mac().computeMac(data);
        // The previous key still verifies inside the keyset; the one before it is gone.
        keysets.signingKey(team, v3.sealed()).mac().verifyMac(tagV2, data);
        byte[] tagV1 = keysets.signingKey(team, v1.sealed()).mac().computeMac(data);
        assertThatThrownBy(() -> keysets.signingKey(team, v3.sealed()).mac().verifyMac(tagV1, data))
                .isInstanceOf(java.security.GeneralSecurityException.class);
    }

    @Test
    void jwtKeysetsRotateKeepingThePreviousKey() {
        SealedKeyset first = keysets.createJwtSigningKeyset();
        KeysetHandle handle = keysets.openPlatformKeyset(PlatformKeyPurpose.JWT_SIGNING, first);
        SealedKeyset second = keysets.rotateJwtSigningKeyset(first);
        SealedKeyset third = keysets.rotateJwtSigningKeyset(second);
        KeysetHandle rotated = keysets.openPlatformKeyset(PlatformKeyPurpose.JWT_SIGNING, third);

        assertThat(handle.size()).isEqualTo(1);
        assertThat(rotated.size()).isEqualTo(2);
        assertThat(rotated.getPrimary().getId())
                .isNotEqualTo(handle.getPrimary().getId());
        assertThatThrownBy(() -> keysets.aead(team, KeyPurpose.SECRETS, first)).isInstanceOf(KeysetException.class);
    }

    @Test
    void cachesOpenedKeysets() {
        SealedKeyset sealed = keysets.createJwtSigningKeyset();

        assertThat(keysets.openPlatformKeyset(PlatformKeyPurpose.JWT_SIGNING, sealed))
                .isSameAs(keysets.openPlatformKeyset(PlatformKeyPurpose.JWT_SIGNING, sealed));
    }

    @Test
    void valueTypesNeverPrintKeyMaterial() {
        NewSigningKey created = keysets.createSigningKey(team);
        String secret = created.exportedKey().reveal();

        assertThat(created.toString()).doesNotContain(secret).doesNotContain(secret.substring(8));
        assertThat(created.sealed().toString()).contains("bytes").contains(LocalKeyEncryptionKey.URI);
        assertThat(keysets.signingKey(team, created.sealed()).toString()).contains("version=1");
        assertThat(keysets.aead(team, KeyPurpose.SECRETS, keysets.createTeamKeyset(team, KeyPurpose.SECRETS))
                        .toString())
                .contains("SECRETS");
        assertThat(kek.toString()).isEqualTo("LocalKeyEncryptionKey[" + LocalKeyEncryptionKey.URI + "]");
        assertThat(keysets.toString()).contains(LocalKeyEncryptionKey.URI);
    }

    @Test
    void sealedKeysetsCompareByContent() {
        SealedKeyset a = new SealedKeyset(new byte[] {1, 2}, "uri");

        assertThat(a)
                .isEqualTo(new SealedKeyset(new byte[] {1, 2}, "uri"))
                .hasSameHashCodeAs(new SealedKeyset(new byte[] {1, 2}, "uri"));
        assertThat(a).isNotEqualTo(new SealedKeyset(new byte[] {1, 3}, "uri"));
        assertThatThrownBy(() -> new SealedKeyset(new byte[0], "uri")).isInstanceOf(IllegalArgumentException.class);
        byte[] copy = a.ciphertext();
        copy[0] = 9;
        assertThat(a.ciphertext()[0]).isEqualTo((byte) 1);
    }

    @Test
    void valueRecordsValidateTheirComponents() {
        assertThatThrownBy(() -> new TeamSigningKey(
                        team,
                        0,
                        keysets.signingKey(team, keysets.createSigningKey(team).sealed())
                                .mac()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TeamAead(team, KeyPurpose.SIGNING, kek.aead()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
