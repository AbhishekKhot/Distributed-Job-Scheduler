package dev.kairos.security.keyset;

import dev.kairos.security.SecretValue;
import java.util.Objects;

/**
 * A newly created or rotated request-signing key.
 *
 * <p>{@code exportedKey} is shown to the team exactly once ({@code signingKey} of LLD APIs 8 and 23) and then only the
 * sealed keyset is kept. Its format is {@code ksig_v<version>_<43 base64url characters>}: 32 random bytes, which are
 * the HMAC-SHA256 key targets verify with.
 *
 * @param sealed the team's SIGNING keyset to store
 * @param version the new key's version
 * @param exportedKey the key for the team; redacted in {@link #toString()}
 */
public record NewSigningKey(SealedKeyset sealed, int version, SecretValue exportedKey) {

    /** Validates the components. */
    public NewSigningKey {
        Objects.requireNonNull(sealed, "sealed");
        Objects.requireNonNull(exportedKey, "exportedKey");
    }
}
