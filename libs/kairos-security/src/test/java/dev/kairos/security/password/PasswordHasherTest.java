package dev.kairos.security.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.security.SecurityFixtures;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    /**
     * Known answer from the Argon2 reference implementation's test suite (argon2id, v=19, t=2, m=2^16 KiB, p=1,
     * password "password", salt "somesalt").
     */
    private static final String REFERENCE_PHC =
            "$argon2id$v=19$m=65536,t=2,p=1$c29tZXNhbHQ$CTFhFdXPJO1aFaMaO6Mm5c8y7cJHAph8ArZWb2GRPPc";

    private static final String REFERENCE_HEX = "09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7";

    private final PasswordHasher hasher = new PasswordHasher(Argon2idParameters.DEFAULT, SecurityFixtures.RANDOM);

    @Test
    void matchesTheReferenceImplementation() {
        byte[] hash = PasswordHasher.derive(
                new Argon2idParameters(65_536, 2, 1, 8, 32),
                "password",
                "somesalt".getBytes(StandardCharsets.US_ASCII));

        assertThat(HexFormat.of().formatHex(hash)).isEqualTo(REFERENCE_HEX);
        assertThat(hasher.verify("password", REFERENCE_PHC)).isTrue();
        assertThat(hasher.verify("Password", REFERENCE_PHC)).isFalse();
    }

    @Test
    void writesPhcStringsWithTheLldParameters() {
        String phc = hasher.hash("correct horse battery staple");

        assertThat(phc).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        String[] parts = phc.split("\\$", -1);
        assertThat(parts[4]).hasSize(22); // 16-byte salt, unpadded base64
        assertThat(parts[5]).hasSize(43); // 32-byte hash, unpadded base64
        assertThat(hasher.verify("correct horse battery staple", phc)).isTrue();
        assertThat(hasher.verify("correct horse battery stapl", phc)).isFalse();
        assertThat(hasher.needsRehash(phc)).isFalse();
    }

    @Test
    void usesAFreshSaltForEveryHash() {
        assertThat(hasher.hash("same password here")).isNotEqualTo(hasher.hash("same password here"));
    }

    @Test
    void normalisesUnicodeBeforeHashing() {
        String composed = "café-password-1";
        String decomposed = "café-password-1";

        assertThat(hasher.verify(decomposed, hasher.hash(composed))).isTrue();
    }

    @Test
    void asksForARehashWhenParametersChange() {
        PasswordHasher stronger =
                new PasswordHasher(new Argon2idParameters(32_768, 3, 1, 16, 32), SecurityFixtures.RANDOM);
        String old = hasher.hash("an old password value");

        assertThat(stronger.needsRehash(old)).isTrue();
        assertThat(stronger.verify("an old password value", old)).isTrue();
        assertThat(stronger.needsRehash("not a hash")).isTrue();
    }

    @Test
    void rejectsMalformedOrOversizedHashes() {
        assertThat(hasher.verify("password", "")).isFalse();
        assertThat(hasher.verify("password", "$argon2i$v=19$m=65536,t=2,p=1$c29tZXNhbHQ$CTFh"))
                .isFalse();
        assertThat(hasher.verify("password", "$argon2id$v=19$m=65536,t=2$c29tZXNhbHQ$CTFh"))
                .isFalse();
        assertThat(hasher.verify("password", "$argon2id$v=19$m=65536,t=2,p=1$c29tZXNhbHQ"))
                .isFalse();
        assertThat(hasher.verify(
                        "password", "$argon2id$v=19$m=6x,t=2,p=1$c29tZXNhbHQ$CTFhFdXPJO1aFaMaO6Mm5c8y7cJHAph8"))
                .isFalse();
        assertThat(hasher.verify("password", "$argon2id$v=19$x=65536,t=2,p=1$c29tZXNhbHQ$CTFhFdXPJO1aFaMaO6Mm5c8y7cJ"))
                .isFalse();
        assertThat(hasher.verify("password", "$argon2id$v=19$m=,t=2,p=1$c29tZXNhbHQ$CTFhFdXPJO1aFaMaO6Mm5c8y7cJHAph"))
                .isFalse();
        assertThat(hasher.verify("password", "$argon2id$v=19$m=65536,t=2,p=1$!!!$CTFhFdXPJO1aFaMaO6Mm5c8y7cJHAph8"))
                .isFalse();
        // A planted hash demanding 4 GiB of memory is refused without computing it.
        assertThat(hasher.verify("password", "$argon2id$v=19$m=4194304,t=2,p=1$c29tZXNhbHQ$CTFhFdXPJO1aFaMaO6Mm5c8y7c"))
                .isFalse();
        assertThat(hasher.verify("password", "$" + "a".repeat(600))).isFalse();
    }

    @Test
    void dummyVerificationRunsWithoutAStoredHash() {
        hasher.verifyDummy("whatever was typed in");

        assertThat(hasher.toString()).contains("19456").doesNotContain("$argon2id");
    }

    @Test
    void rejectsParametersBelowTheMinimums() {
        assertThatThrownBy(() -> new Argon2idParameters(4, 1, 1, 16, 32)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Argon2idParameters(19_456, 0, 1, 16, 32))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Argon2idParameters(19_456, 2, 1, 4, 32))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Argon2idParameters(19_456, 2, 1, 16, 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Argon2idParameters(19_456, 17, 1, 16, 32).requireWithinLimits())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
