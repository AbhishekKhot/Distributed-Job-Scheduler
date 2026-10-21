package dev.kairos.api.support.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.TeamId;
import dev.kairos.testsupport.TestIds;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CursorCodecTest {

    private final CursorCodec codec = new CursorCodec(randomKey());
    private final TeamId team = TestIds.teamId();

    @Test
    void roundTripsTheSortKeys() {
        List<String> values = List.of("2026-10-21T10:00:00.123456Z", "0192f3a1-7c2e-7b4d-9a10-3f5e2c8d41c9", "é");

        String cursor = codec.encode("jobs", team, values);

        assertThat(cursor).matches("[A-Za-z0-9_-]+");
        assertThat(codec.decode("jobs", team, cursor)).isEqualTo(values);
    }

    @Test
    void anEditedCursorIsRejected() {
        String cursor = codec.encode("jobs", team, List.of("a"));
        byte[] raw = Base64.getUrlDecoder().decode(cursor);
        raw[2] ^= 1;
        String edited = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        assertInvalid(() -> codec.decode("jobs", team, edited));
    }

    @Test
    void aCursorOnlyWorksForItsListTeamAndKey() {
        String cursor = codec.encode("jobs", team, List.of("a"));

        assertInvalid(() -> codec.decode("runs", team, cursor));
        assertInvalid(() -> codec.decode("jobs", TestIds.teamId(), cursor));
        assertInvalid(() -> new CursorCodec(randomKey()).decode("jobs", team, cursor));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "not base64!", "AAAA", "=="})
    void garbageIsRejected(String cursor) {
        assertInvalid(() -> codec.decode("jobs", team, cursor));
    }

    @Test
    void anOverlongCursorIsRejectedBeforeDecoding() {
        assertInvalid(() -> codec.decode("jobs", team, "A".repeat(CursorCodec.MAX_LENGTH + 1)));
    }

    @Test
    void encodingChecksItsInput() {
        assertThatThrownBy(() -> codec.encode("jobs", team, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encode("jobs", team, List.of("x".repeat(257))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CursorCodec(new byte[16])).isInstanceOf(IllegalArgumentException.class);
        assertThat(codec.toString()).isEqualTo("CursorCodec[key=<redacted>]");
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ValidationFailedException.class,
                        e -> assertThat(e.fieldErrors()).containsExactly(new FieldError("cursor", FieldError.FORMAT)));
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
