package dev.kairos.api.support.etag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.PreconditionFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ETagsTest {

    @Test
    void formatsAndParsesStrongVersionTags() {
        assertThat(ETags.of(3)).isEqualTo("\"3\"");
        assertThat(ETags.expectedVersion("\"3\"")).isEqualTo(3);
        assertThat(ETags.expectedVersion(" \"12\" ")).isEqualTo(12);
        assertThatThrownBy(() -> ETags.of(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void aMissingHeaderIs428(String header) {
        assertThatThrownBy(() -> ETags.expectedVersion(header))
                .isInstanceOfSatisfying(
                        PreconditionFailedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.IF_MATCH_REQUIRED));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"*", "3", "W/\"3\"", "\"3\", \"4\"", "\"0\"", "\"-1\"", "\"abc\"", "\"\"", "\"99999999999\""})
    void anythingButOneStrongVersionIs412(String header) {
        assertThatThrownBy(() -> ETags.expectedVersion(header))
                .isInstanceOfSatisfying(
                        PreconditionFailedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VERSION_MISMATCH));
    }

    @Test
    void comparesVersions() {
        ETags.requireVersion(4, 4);
        assertThatThrownBy(() -> ETags.requireVersion(3, 4)).isInstanceOf(PreconditionFailedException.class);
    }
}
