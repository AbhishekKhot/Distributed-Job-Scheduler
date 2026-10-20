package dev.kairos.security.apikey;

import dev.kairos.security.RandomTokens;
import dev.kairos.security.SecretValue;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Issues, parses and verifies team API keys (LLD section 2.2, {@code api_keys}).
 *
 * <p>Format: {@code kairos_<keyId>_<secret>}, where {@code keyId} is 16 characters from {@code [A-Za-z0-9]} (the
 * public primary key) and {@code secret} is 32 random bytes in unpadded base64url (43 characters). Only
 * {@code HMAC-SHA256(pepper, secret)} and the pepper's version are stored; the full key is returned once, at creation.
 *
 * <p>Verification recomputes the HMAC under the pepper of the stored version and compares in constant time. A match
 * under a pepper that is no longer current is reported as {@link ApiKeyMatch#MATCH_OUTDATED_PEPPER}, so the caller can
 * store {@link #hash(PresentedApiKey)} with the current pepper; once every row is re-hashed or expired, the previous
 * pepper is removed from configuration.
 *
 * <p>Thread-safe. Keys and secrets never appear in {@code toString()} or exceptions.
 */
public final class ApiKeyService {

    /** The prefix every Kairos API key starts with. */
    public static final String PREFIX = "kairos_";

    /** The length of the public key id. */
    public static final int KEY_ID_LENGTH = 16;

    private static final int SECRET_BYTES = 32;
    private static final int SECRET_LENGTH = 43;
    private static final int KEY_LENGTH = PREFIX.length() + KEY_ID_LENGTH + 1 + SECRET_LENGTH;
    private static final String HMAC = "HmacSHA256";

    private final ApiKeyPeppers peppers;
    private final SecureRandom random;

    /**
     * Creates the service.
     *
     * @param peppers the current and previous peppers
     * @param random the CSPRNG for key ids and secrets
     */
    public ApiKeyService(ApiKeyPeppers peppers, SecureRandom random) {
        this.peppers = Objects.requireNonNull(peppers, "peppers");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Creates a new key.
     *
     * @return the key to return once, and the hash to store
     */
    public IssuedApiKey issue() {
        String keyId = RandomTokens.alphanumeric(random, KEY_ID_LENGTH);
        String secret = RandomTokens.base64Url(random, SECRET_BYTES);
        byte[] hash = hmac(peppers.current(), secret);
        return new IssuedApiKey(keyId, SecretValue.of(PREFIX + keyId + "_" + secret), hash, peppers.currentVersion());
    }

    /**
     * Tells whether a bearer credential has the API key shape (rather than a JWT). It does not validate it.
     *
     * @param credential the bearer credential
     * @return whether it starts with {@value #PREFIX}
     */
    public static boolean looksLikeApiKey(String credential) {
        return credential.startsWith(PREFIX);
    }

    /**
     * Parses a presented key. The check is structural only (prefix, length, alphabets), done without regular
     * expressions on input whose length is checked first.
     *
     * @param presented the bearer credential
     * @return the key id and secret, or empty if it is not a well-formed Kairos API key
     */
    public Optional<PresentedApiKey> parse(String presented) {
        if (presented.length() != KEY_LENGTH || !presented.startsWith(PREFIX)) {
            return Optional.empty();
        }
        int idStart = PREFIX.length();
        int separator = idStart + KEY_ID_LENGTH;
        if (presented.charAt(separator) != '_') {
            return Optional.empty();
        }
        for (int i = idStart; i < separator; i++) {
            if (!RandomTokens.isAlphanumeric(presented.charAt(i))) {
                return Optional.empty();
            }
        }
        for (int i = separator + 1; i < presented.length(); i++) {
            if (!RandomTokens.isBase64Url(presented.charAt(i))) {
                return Optional.empty();
            }
        }
        return Optional.of(new PresentedApiKey(
                presented.substring(idStart, separator), SecretValue.of(presented.substring(separator + 1))));
    }

    /**
     * Verifies a presented key against its stored hash in constant time.
     *
     * @param presented the parsed key
     * @param storedHash {@code api_keys.secret_hash}
     * @param pepperVersion {@code api_keys.pepper_version}
     * @return the outcome; {@link ApiKeyMatch#NO_MATCH} also when the stored pepper version is no longer configured
     */
    public ApiKeyMatch verify(PresentedApiKey presented, byte[] storedHash, int pepperVersion) {
        Optional<byte[]> pepper = peppers.version(pepperVersion);
        if (pepper.isEmpty()) {
            return ApiKeyMatch.NO_MATCH;
        }
        byte[] candidate = hmac(pepper.get(), presented.secret().reveal());
        if (!MessageDigest.isEqual(candidate, storedHash)) {
            return ApiKeyMatch.NO_MATCH;
        }
        return pepperVersion == peppers.currentVersion() ? ApiKeyMatch.MATCH : ApiKeyMatch.MATCH_OUTDATED_PEPPER;
    }

    /**
     * Hashes a presented key with the current pepper, to replace a hash made with a previous pepper.
     *
     * @param presented the parsed key, already verified
     * @return the new hash; store it with {@link #currentPepperVersion()}
     */
    public byte[] hash(PresentedApiKey presented) {
        return hmac(peppers.current(), presented.secret().reveal());
    }

    /**
     * Returns the pepper version new hashes are written with.
     *
     * @return the version
     */
    public int currentPepperVersion() {
        return peppers.currentVersion();
    }

    private static byte[] hmac(byte[] pepper, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(pepper, HMAC));
            return mac.doFinal(secret.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is required by every JDK", e);
        }
    }

    @Override
    public String toString() {
        return "ApiKeyService[" + peppers + "]";
    }
}
