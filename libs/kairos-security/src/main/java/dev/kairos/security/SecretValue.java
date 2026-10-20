package dev.kairos.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A secret string that is handed to a caller exactly once, for example a new API key, refresh token or signing key.
 *
 * <p>The value never appears in {@link #toString()}, so a secret cannot leak into a log line or an exception message
 * by accident; it is read only through {@link #reveal()}, at the single place that returns it to its owner. Equality is
 * constant-time.
 *
 * <p>Immutable and thread-safe.
 */
public final class SecretValue {

    private static final String REDACTED = "SecretValue[<redacted>]";

    private final String value;

    private SecretValue(String value) {
        this.value = value;
    }

    /**
     * Wraps a secret.
     *
     * @param value the secret, not empty
     * @return the wrapper
     * @throws IllegalArgumentException if {@code value} is empty
     */
    public static SecretValue of(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("a secret must not be empty");
        }
        return new SecretValue(value);
    }

    /**
     * Returns the secret. Call it only where the secret leaves the process to its owner (a one-time API response) or
     * enters a cryptographic operation.
     *
     * @return the secret
     */
    public String reveal() {
        return value;
    }

    /** Compares in constant time. */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof SecretValue that
                && MessageDigest.isEqual(
                        value.getBytes(StandardCharsets.UTF_8), that.value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int hashCode() {
        // A constant: a hash of the secret would leak information about it, and secrets are never map keys.
        return 0;
    }

    /** Returns a fixed text without the secret. */
    @Override
    public String toString() {
        return REDACTED;
    }
}
