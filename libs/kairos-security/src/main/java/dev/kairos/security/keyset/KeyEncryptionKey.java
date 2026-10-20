package dev.kairos.security.keyset;

import com.google.crypto.tink.Aead;

/**
 * The key encryption key (KEK) that seals every Kairos keyset at rest.
 *
 * <p>In production it is a Cloud KMS key ({@link GcpKmsKeyEncryptionKey}): the key material never leaves KMS, and each
 * seal or unseal is a KMS call. Locally it is a 32-byte key from {@code KAIROS_LOCAL_KEK}
 * ({@link LocalKeyEncryptionKey}), available only under the {@code local} profile.
 *
 * <p>Implementations are thread-safe.
 */
public interface KeyEncryptionKey {

    /**
     * Returns the key's URI, stored next to each sealed keyset ({@code kek_uri}) so a keyset is never opened with the
     * wrong key, and so keysets can be re-sealed when the KEK changes.
     *
     * @return the URI, for example {@code gcp-kms://projects/p/locations/l/keyRings/r/cryptoKeys/k}
     */
    String uri();

    /**
     * Returns the AEAD that seals and opens keysets.
     *
     * @return the AEAD
     */
    Aead aead();
}
