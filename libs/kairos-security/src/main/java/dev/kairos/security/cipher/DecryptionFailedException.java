package dev.kairos.security.cipher;

/**
 * A ciphertext did not decrypt: it was altered, written for another row (associated data mismatch) or under a key
 * that is no longer in the keyset. The message is fixed; it never contains ciphertext or plaintext.
 */
public final class DecryptionFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param cause the Tink failure
     */
    public DecryptionFailedException(Throwable cause) {
        super("the value cannot be decrypted", cause);
    }
}
