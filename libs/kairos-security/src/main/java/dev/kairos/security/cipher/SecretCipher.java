package dev.kairos.security.cipher;

import dev.kairos.domain.id.RunId;
import dev.kairos.security.keyset.KeyPurpose;
import dev.kairos.security.keyset.TeamAead;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * Encrypts and decrypts team secrets and sensitive workflow outputs with AES-256-GCM through the team's Tink keyset.
 *
 * <p>Every ciphertext is bound to its row through associated data:
 *
 * <ul>
 *   <li>secrets: {@code <teamId>|<name>|<version>}, the primary key of {@code team_secrets};
 *   <li>outputs: {@code <teamId>|<runId>|<outputName>}.
 * </ul>
 *
 * <p>A ciphertext moved to another team, name, version, run or output therefore fails to decrypt instead of yielding
 * the wrong secret. Secrets use the team's {@link KeyPurpose#SECRETS} keyset and outputs its {@link KeyPurpose#OUTPUTS}
 * keyset; passing the other one is rejected. Tink picks a fresh random nonce per encryption and prefixes the key id,
 * so keys can rotate without re-encrypting old rows.
 *
 * <p>Stateless and thread-safe. Plaintext is never logged or included in an exception.
 */
public final class SecretCipher {

    /** The largest plaintext accepted; {@code team_secrets.ciphertext} is capped at 16 KB. */
    public static final int MAX_PLAINTEXT_BYTES = 16 * 1024 - 64;

    /** Creates the cipher. */
    public SecretCipher() {}

    /**
     * Encrypts a team secret.
     *
     * @param keys the team's SECRETS keyset
     * @param name the secret name
     * @param version the secret version
     * @param plaintext the secret
     * @return the ciphertext for {@code team_secrets.ciphertext}
     */
    public byte[] encryptSecret(TeamAead keys, String name, int version, byte[] plaintext) {
        requirePurpose(keys, KeyPurpose.SECRETS);
        return encrypt(keys, plaintext, secretAssociatedData(keys, name, version));
    }

    /**
     * Decrypts a team secret.
     *
     * @param keys the team's SECRETS keyset
     * @param name the secret name
     * @param version the secret version
     * @param ciphertext the stored ciphertext
     * @return the secret
     * @throws DecryptionFailedException if the ciphertext was not written for this team, name and version, or was
     *     altered
     */
    public byte[] decryptSecret(TeamAead keys, String name, int version, byte[] ciphertext) {
        requirePurpose(keys, KeyPurpose.SECRETS);
        return decrypt(keys, ciphertext, secretAssociatedData(keys, name, version));
    }

    /**
     * Seals a sensitive output of a run.
     *
     * @param keys the team's OUTPUTS keyset
     * @param run the run that produced the output
     * @param outputName the output name
     * @param plaintext the output value
     * @return the sealed value
     */
    public byte[] encryptOutput(TeamAead keys, RunId run, String outputName, byte[] plaintext) {
        requirePurpose(keys, KeyPurpose.OUTPUTS);
        return encrypt(keys, plaintext, outputAssociatedData(keys, run, outputName));
    }

    /**
     * Opens a sensitive output of a run.
     *
     * @param keys the team's OUTPUTS keyset
     * @param run the run that produced the output
     * @param outputName the output name
     * @param ciphertext the sealed value
     * @return the output value
     * @throws DecryptionFailedException if the value was not sealed for this team, run and output, or was altered
     */
    public byte[] decryptOutput(TeamAead keys, RunId run, String outputName, byte[] ciphertext) {
        requirePurpose(keys, KeyPurpose.OUTPUTS);
        return decrypt(keys, ciphertext, outputAssociatedData(keys, run, outputName));
    }

    private static byte[] encrypt(TeamAead keys, byte[] plaintext, byte[] associatedData) {
        Objects.requireNonNull(plaintext, "plaintext");
        if (plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("the value is too large to encrypt");
        }
        try {
            return keys.aead().encrypt(plaintext, associatedData);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    private static byte[] decrypt(TeamAead keys, byte[] ciphertext, byte[] associatedData) {
        Objects.requireNonNull(ciphertext, "ciphertext");
        try {
            return keys.aead().decrypt(ciphertext, associatedData);
        } catch (GeneralSecurityException e) {
            throw new DecryptionFailedException(e);
        }
    }

    private static void requirePurpose(TeamAead keys, KeyPurpose purpose) {
        if (keys.purpose() != purpose) {
            throw new IllegalArgumentException("the keyset has the wrong purpose");
        }
    }

    static byte[] secretAssociatedData(TeamAead keys, String name, int version) {
        Objects.requireNonNull(name, "name");
        return (keys.team() + "|" + name + "|" + version).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] outputAssociatedData(TeamAead keys, RunId run, String outputName) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(outputName, "outputName");
        return (keys.team() + "|" + run + "|" + outputName).getBytes(StandardCharsets.UTF_8);
    }
}
