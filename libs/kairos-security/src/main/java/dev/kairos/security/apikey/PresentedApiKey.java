package dev.kairos.security.apikey;

import dev.kairos.security.SecretValue;
import java.util.Objects;

/**
 * A well-formed API key presented by a caller, split into the id used to find the row and the secret to verify.
 *
 * @param keyId the public key id
 * @param secret the secret part; redacted in {@link #toString()}
 */
public record PresentedApiKey(String keyId, SecretValue secret) {

    /** Validates the components. */
    public PresentedApiKey {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(secret, "secret");
    }
}
