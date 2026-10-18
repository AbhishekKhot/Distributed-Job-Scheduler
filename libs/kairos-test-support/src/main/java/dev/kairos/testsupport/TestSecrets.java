package dev.kairos.testsupport;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generates the passwords the test containers are started with.
 *
 * <p>Passwords are new for every test run and never written to a file or a log. Hex keeps them safe for every place
 * they end up: shell variables, psql literals, the Redis ACL file (which must be alphanumeric) and JDBC properties.
 */
final class TestSecrets {

    /** 32 random bytes, so 64 hex characters, matching {@code scripts/dev-env.sh}. */
    private static final int BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private TestSecrets() {}

    static String randomHex() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
