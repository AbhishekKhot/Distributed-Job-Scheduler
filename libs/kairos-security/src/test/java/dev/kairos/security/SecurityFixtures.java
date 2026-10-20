package dev.kairos.security;

import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.LocalKeyEncryptionKey;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/** Shared test fixtures: random hex keys, a local key encryption key and a keyset service on it. */
public final class SecurityFixtures {

    /** One CSPRNG for the tests; values are random per run and never written anywhere. */
    public static final SecureRandom RANDOM = new SecureRandom();

    private SecurityFixtures() {}

    /**
     * Returns 32 random bytes as 64 hex characters, the format of every Kairos key in the environment.
     *
     * @return the hex key
     */
    public static String randomHexKey() {
        return HexFormat.of().formatHex(RandomTokens.bytes(RANDOM, 32));
    }

    /**
     * Returns a local key encryption key with a new random key.
     *
     * @return the key
     */
    public static LocalKeyEncryptionKey localKek() {
        return LocalKeyEncryptionKey.forLocalProfile(randomHexKey(), true);
    }

    /**
     * Returns a keyset service on a new local key encryption key.
     *
     * @return the service
     */
    public static KeysetService keysetService() {
        return keysetService(localKek());
    }

    /**
     * Returns a keyset service on the given key encryption key.
     *
     * @param kek the key encryption key
     * @return the service
     */
    public static KeysetService keysetService(LocalKeyEncryptionKey kek) {
        return new KeysetService(kek, RANDOM, Duration.ofMinutes(10), 100);
    }
}
