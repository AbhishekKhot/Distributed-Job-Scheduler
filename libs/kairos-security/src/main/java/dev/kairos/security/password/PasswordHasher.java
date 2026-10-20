package dev.kairos.security.password;

import dev.kairos.security.RandomTokens;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Hashes and verifies passwords with Argon2id (Bouncy Castle's lightweight API), stored as PHC strings.
 *
 * <p>Format: {@code $argon2id$v=19$m=<KiB>,t=<iterations>,p=<lanes>$<salt>$<hash>}, salt and hash in unpadded
 * standard base64, as written by the reference implementation, so hashes stay portable. New hashes use the
 * configured {@link Argon2idParameters} and a fresh salt from the injected {@link SecureRandom}. Verification reads the
 * parameters from the stored string (within {@link Argon2idParameters#requireWithinLimits() limits}) and compares in
 * constant time; {@link #needsRehash(String)} tells the caller to store a new hash after a successful login when the
 * parameters have been raised.
 *
 * <p>Passwords are normalised to Unicode NFC before hashing, so the same password typed on different platforms gives
 * the same hash. The UTF-8 bytes are wiped after use. Neither the password nor the hash is ever logged.
 *
 * <p>Thread-safe.
 */
public final class PasswordHasher {

    private static final String PREFIX = "$argon2id$v=19$";
    private static final int MAX_ENCODED_LENGTH = 512;
    private static final int MAX_PARAMETER_DIGITS = 7;
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getDecoder();

    private final Argon2idParameters parameters;
    private final SecureRandom random;
    private final String dummyHash;

    /**
     * Creates a hasher.
     *
     * @param parameters the parameters new hashes are written with
     * @param random the CSPRNG for salts
     */
    public PasswordHasher(Argon2idParameters parameters, SecureRandom random) {
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.random = Objects.requireNonNull(random, "random");
        // A hash of a random password with the current parameters: verifying against it costs exactly as much as a
        // real verification, which keeps a login for an unknown email from being faster than one for a known email.
        this.dummyHash = hash(RandomTokens.base64Url(random, 32));
    }

    /**
     * Hashes a password with a fresh salt.
     *
     * @param password the password
     * @return the PHC string
     */
    public String hash(CharSequence password) {
        byte[] salt = RandomTokens.bytes(random, parameters.saltLength());
        byte[] hash = derive(parameters, password, salt);
        return format(parameters, salt, hash);
    }

    /**
     * Verifies a password against a stored hash in constant time.
     *
     * @param password the presented password
     * @param encoded the stored PHC string
     * @return whether the password matches; {@code false} also when the stored string is malformed or its cost
     *     parameters exceed the limits
     */
    public boolean verify(CharSequence password, String encoded) {
        Optional<Parsed> parsed = parse(encoded);
        if (parsed.isEmpty()) {
            return false;
        }
        Parsed stored = parsed.get();
        byte[] candidate = derive(stored.parameters(), password, stored.salt());
        try {
            return MessageDigest.isEqual(candidate, stored.hash());
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    /**
     * Spends the time of one verification without a stored hash. Call it when the account does not exist (or is
     * disabled), so the response time does not reveal which emails are registered.
     *
     * @param password the presented password
     */
    public void verifyDummy(CharSequence password) {
        verify(password, dummyHash);
    }

    /**
     * Tells whether a stored hash was written with parameters other than the current ones.
     *
     * @param encoded the stored PHC string
     * @return {@code true} if the hash should be replaced after the next successful verification, including when it
     *     cannot be parsed
     */
    public boolean needsRehash(String encoded) {
        return parse(encoded)
                .map(stored -> !stored.parameters().equals(parameters))
                .orElse(true);
    }

    static byte[] derive(Argon2idParameters parameters, CharSequence password, byte[] salt) {
        Argon2Parameters argon2 = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(parameters.memoryKib())
                .withIterations(parameters.iterations())
                .withParallelism(parameters.parallelism())
                .withSalt(salt)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(argon2);
        byte[] passwordBytes = utf8(Normalizer.normalize(password, Normalizer.Form.NFC));
        byte[] hash = new byte[parameters.hashLength()];
        try {
            generator.generateBytes(passwordBytes, hash);
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
        }
        return hash;
    }

    private static byte[] utf8(String text) {
        // String.getBytes replaces unpaired surrogates, so every input hashes deterministically.
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static String format(Argon2idParameters parameters, byte[] salt, byte[] hash) {
        return PREFIX + "m=" + parameters.memoryKib() + ",t=" + parameters.iterations() + ",p="
                + parameters.parallelism() + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(hash);
    }

    private static Optional<Parsed> parse(String encoded) {
        if (encoded.length() > MAX_ENCODED_LENGTH || !encoded.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] parts = encoded.substring(PREFIX.length()).split("\\$", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        String[] costs = parts[0].split(",", -1);
        if (costs.length != 3) {
            return Optional.empty();
        }
        try {
            int memory = cost(costs[0], "m=");
            int iterations = cost(costs[1], "t=");
            int parallelism = cost(costs[2], "p=");
            byte[] salt = B64_DECODER.decode(parts[1]);
            byte[] hash = B64_DECODER.decode(parts[2]);
            Argon2idParameters stored = new Argon2idParameters(
                            memory, iterations, parallelism, salt.length, hash.length)
                    .requireWithinLimits();
            return Optional.of(new Parsed(stored, salt, hash));
        } catch (IllegalArgumentException e) {
            // Malformed numbers, base64 or out-of-range costs: the hash cannot match anything.
            return Optional.empty();
        }
    }

    private static int cost(String part, String name) {
        if (!part.startsWith(name)) {
            throw new IllegalArgumentException("unexpected cost parameter");
        }
        String digits = part.substring(name.length());
        if (digits.isEmpty() || digits.length() > MAX_PARAMETER_DIGITS) {
            throw new IllegalArgumentException("cost parameter out of range");
        }
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                throw new IllegalArgumentException("cost parameter is not a number");
            }
        }
        return Integer.parseInt(digits);
    }

    /** Leaves the hashes out. */
    @Override
    public String toString() {
        return "PasswordHasher[" + parameters + "]";
    }

    @SuppressWarnings("ArrayRecordComponent") // Private carrier; the arrays never leave this class.
    private record Parsed(Argon2idParameters parameters, byte[] salt, byte[] hash) {}
}
