package dev.kairos.migrations;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The local key encryption key the tests run the job with.
 *
 * <p>One random key per test JVM: every test class migrates the same control database, so they must all seal and open
 * the platform keyset with the same key. It is never written to a file or a log.
 */
final class TestKek {

    private static final String LOCAL_KEY = newKey();

    private TestKek() {}

    /**
     * Returns the key in the format of {@code KAIROS_LOCAL_KEK}: 32 bytes as 64 hex characters.
     *
     * @return the key
     */
    static String localKey() {
        return LOCAL_KEY;
    }

    private static String newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return HexFormat.of().formatHex(key);
    }
}
