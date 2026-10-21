package dev.kairos.api.security;

import dev.kairos.domain.id.TeamId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Reads stored API keys ({@code api_keys} in the control database) for authentication. */
public interface ApiKeyLookup {

    /**
     * Finds a key by its public id.
     *
     * @param keyId the public key id
     * @return the stored key, or empty if it does not exist
     */
    Optional<StoredApiKey> find(String keyId);

    /**
     * Replaces a hash made with a previous pepper by one made with the current pepper, if the key still has the old
     * pepper version.
     *
     * @param keyId the public key id
     * @param oldPepperVersion the pepper version the stored hash was made with
     * @param newHash the hash under the current pepper
     * @param newPepperVersion the current pepper version
     */
    void upgradeHash(String keyId, int oldPepperVersion, byte[] newHash, int newPepperVersion);

    /**
     * A stored key. The secret itself is never stored, only its HMAC.
     *
     * @param keyId the public key id
     * @param team the key's team
     * @param secretHash HMAC-SHA256 of the secret under the pepper of {@code pepperVersion}
     * @param pepperVersion the pepper version
     * @param expiresAt when the key expires, or {@code null} for never
     * @param revokedAt when the key was revoked, or {@code null}
     */
    record StoredApiKey(
            String keyId,
            TeamId team,
            byte[] secretHash,
            int pepperVersion,
            @Nullable Instant expiresAt,
            @Nullable Instant revokedAt) {

        /** Validates the components and copies the hash. */
        public StoredApiKey {
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(team, "team");
            secretHash = secretHash.clone();
        }

        @Override
        public byte[] secretHash() {
            return secretHash.clone();
        }

        /**
         * Tells whether the key can be used at a time.
         *
         * @param now the time
         * @return whether it is neither revoked nor expired
         */
        public boolean usableAt(Instant now) {
            return revokedAt == null && (expiresAt == null || now.isBefore(expiresAt));
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof StoredApiKey that
                    && keyId.equals(that.keyId)
                    && team.equals(that.team)
                    && java.security.MessageDigest.isEqual(secretHash, that.secretHash)
                    && pepperVersion == that.pepperVersion
                    && Objects.equals(expiresAt, that.expiresAt)
                    && Objects.equals(revokedAt, that.revokedAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(keyId, team, pepperVersion, expiresAt, revokedAt);
        }

        /** Leaves the hash out. */
        @Override
        public String toString() {
            return "StoredApiKey[keyId=" + keyId + ", team=" + team + ", pepperVersion=" + pepperVersion
                    + ", expiresAt=" + expiresAt + ", revokedAt=" + revokedAt + "]";
        }
    }
}
