package dev.kairos.security;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Random values for tokens, key ids and secrets, always drawn from a {@link SecureRandom} supplied by the caller.
 *
 * <p>Stateless and thread-safe (as thread-safe as the {@link SecureRandom} passed in, which is thread-safe in the
 * JDK).
 */
public final class RandomTokens {

    private static final char[] ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private RandomTokens() {}

    /**
     * Returns {@code byteCount} random bytes.
     *
     * @param random the CSPRNG
     * @param byteCount the number of bytes, at least 1
     * @return new random bytes
     */
    public static byte[] bytes(SecureRandom random, int byteCount) {
        if (byteCount < 1) {
            throw new IllegalArgumentException("byteCount must be positive");
        }
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return bytes;
    }

    /**
     * Returns {@code byteCount} random bytes encoded as unpadded base64url.
     *
     * @param random the CSPRNG
     * @param byteCount the number of bytes, at least 1
     * @return the encoded bytes, for example 43 characters for 32 bytes
     */
    public static String base64Url(SecureRandom random, int byteCount) {
        return BASE64_URL.encodeToString(bytes(random, byteCount));
    }

    /**
     * Returns a string of {@code length} characters from {@code [A-Za-z0-9]}, each chosen uniformly.
     *
     * @param random the CSPRNG
     * @param length the number of characters, at least 1
     * @return the string
     */
    public static String alphanumeric(SecureRandom random, int length) {
        if (length < 1) {
            throw new IllegalArgumentException("length must be positive");
        }
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            // nextInt(bound) rejects out-of-range draws, so every character is equally likely (no modulo bias).
            chars[i] = ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)];
        }
        return new String(chars);
    }

    /**
     * Encodes bytes as unpadded base64url.
     *
     * @param bytes the bytes
     * @return the encoding
     */
    public static String encodeBase64Url(byte[] bytes) {
        return BASE64_URL.encodeToString(bytes);
    }

    /**
     * Tells whether a character is in {@code [A-Za-z0-9]}.
     *
     * @param c the character
     * @return whether it is an ASCII letter or digit
     */
    public static boolean isAlphanumeric(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /**
     * Tells whether a character is in the base64url alphabet {@code [A-Za-z0-9_-]}.
     *
     * @param c the character
     * @return whether it is a base64url character
     */
    public static boolean isBase64Url(char c) {
        return isAlphanumeric(c) || c == '-' || c == '_';
    }
}
