package dev.kairos.security.keyset;

/**
 * A keyset could not be created, sealed or opened: the key encryption key is wrong or unavailable, the stored keyset
 * is corrupt, or it belongs to another owner or purpose.
 *
 * <p>The message is fixed and never contains key material or ciphertext. Callers treat it as an internal error (the
 * API answers 500 or 503); it is never shown to clients in detail.
 */
public final class KeysetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message a fixed description
     * @param cause the underlying failure
     */
    public KeysetException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates the exception without a cause.
     *
     * @param message a fixed description
     */
    public KeysetException(String message) {
        super(message);
    }
}
