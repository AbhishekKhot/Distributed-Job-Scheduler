package dev.kairos.security.keyset;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A Tink keyset encrypted by the key encryption key, as stored in {@code team_keysets} and {@code platform_keysets}
 * ({@code encrypted_keyset}, {@code kek_uri}).
 *
 * <p>The ciphertext is bound to its owner and purpose through associated data, so a sealed keyset copied to another
 * team's row does not open. Immutable: the bytes are copied in and out.
 */
public final class SealedKeyset {

    private final byte[] ciphertext;
    private final String kekUri;

    /**
     * Creates a sealed keyset.
     *
     * @param ciphertext the encrypted keyset; copied
     * @param kekUri the URI of the key encryption key that sealed it
     */
    public SealedKeyset(byte[] ciphertext, String kekUri) {
        Objects.requireNonNull(ciphertext, "ciphertext");
        Objects.requireNonNull(kekUri, "kekUri");
        if (ciphertext.length == 0 || kekUri.isBlank()) {
            throw new IllegalArgumentException("a sealed keyset needs a ciphertext and a key encryption key URI");
        }
        this.ciphertext = ciphertext.clone();
        this.kekUri = kekUri;
    }

    /**
     * Returns the encrypted keyset.
     *
     * @return a copy of the ciphertext
     */
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    /**
     * Returns the URI of the key encryption key that sealed the keyset.
     *
     * @return the URI
     */
    public String kekUri() {
        return kekUri;
    }

    /** Equal when the ciphertext and the URI are equal. */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof SealedKeyset that
                && kekUri.equals(that.kekUri)
                && MessageDigest.isEqual(ciphertext, that.ciphertext);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(ciphertext) + kekUri.hashCode();
    }

    /** Shows the size, never the bytes. */
    @Override
    public String toString() {
        return "SealedKeyset[" + ciphertext.length + " bytes, kek=" + kekUri + "]";
    }
}
