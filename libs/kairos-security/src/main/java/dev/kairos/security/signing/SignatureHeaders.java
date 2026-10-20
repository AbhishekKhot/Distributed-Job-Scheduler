package dev.kairos.security.signing;

import java.util.Objects;

/**
 * The signature headers of one request.
 *
 * @param timestamp the value of {@value RequestSigner#TIMESTAMP_HEADER}
 * @param signature the value of {@value RequestSigner#SIGNATURE_HEADER}
 */
public record SignatureHeaders(String timestamp, String signature) {

    /** Validates the components. */
    public SignatureHeaders {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(signature, "signature");
    }
}
