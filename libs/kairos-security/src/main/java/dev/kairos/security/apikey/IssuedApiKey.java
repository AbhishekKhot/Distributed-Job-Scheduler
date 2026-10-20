package dev.kairos.security.apikey;

import dev.kairos.security.SecretValue;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A newly created API key.
 *
 * <p>Immutable: the hash is copied in and out. {@link #toString()} shows the key id only.
 */
public final class IssuedApiKey {

    private final String keyId;
    private final SecretValue key;
    private final byte[] secretHash;
    private final int pepperVersion;

    /**
     * Creates the value.
     *
     * @param keyId the public key id ({@code api_keys.key_id})
     * @param key the full key, returned to the caller once
     * @param secretHash the HMAC to store ({@code api_keys.secret_hash}); copied
     * @param pepperVersion the pepper version to store ({@code api_keys.pepper_version})
     */
    public IssuedApiKey(String keyId, SecretValue key, byte[] secretHash, int pepperVersion) {
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        this.key = Objects.requireNonNull(key, "key");
        this.secretHash = secretHash.clone();
        this.pepperVersion = pepperVersion;
    }

    /**
     * Returns the public key id.
     *
     * @return the key id
     */
    public String keyId() {
        return keyId;
    }

    /**
     * Returns the full key, to show once.
     *
     * @return the key
     */
    public SecretValue key() {
        return key;
    }

    /**
     * Returns the hash to store.
     *
     * @return a copy of the HMAC
     */
    public byte[] secretHash() {
        return secretHash.clone();
    }

    /**
     * Returns the pepper version to store.
     *
     * @return the version
     */
    public int pepperVersion() {
        return pepperVersion;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof IssuedApiKey that
                && keyId.equals(that.keyId)
                && key.equals(that.key)
                && MessageDigest.isEqual(secretHash, that.secretHash)
                && pepperVersion == that.pepperVersion;
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyId, pepperVersion) * 31 + Arrays.hashCode(secretHash);
    }

    @Override
    public String toString() {
        return "IssuedApiKey[keyId=" + keyId + ", pepperVersion=" + pepperVersion + "]";
    }
}
