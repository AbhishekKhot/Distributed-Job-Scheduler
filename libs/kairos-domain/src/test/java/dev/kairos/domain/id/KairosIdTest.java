package dev.kairos.domain.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.shard.Shard;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KairosIdTest {

    /** The job id used in the LLD examples. */
    private static final String SAMPLE = "0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e";

    @Test
    void readsShardAndTimestamp() {
        UUID id = UUID.fromString(SAMPLE);

        assertThat(KairosId.shardOf(id)).isEqualTo(Shard.of(0x0e));
        assertThat(KairosId.timestampOf(id)).isEqualTo(Instant.ofEpochMilli(0x0192e8c42a1bL));
    }

    @Test
    void parsesCanonicalTextInEitherCase() {
        assertThat(KairosId.parse("jobId", SAMPLE)).isEqualTo(UUID.fromString(SAMPLE));
        assertThat(KairosId.parse("jobId", SAMPLE.toUpperCase(java.util.Locale.ROOT)))
                .isEqualTo(UUID.fromString(SAMPLE));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "0192e8c4",
                "0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0",
                "0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e0",
                "0192e8c4x2a1b-7f3c-8d4e-5f6a7b8c9d0e",
                "0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0g",
                "0192e8c42a1b7f3c8d4e5f6a7b8c9d0e----",
                "{0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d}",
                // Version 4 and the wrong variant are not Kairos ids.
                "0192e8c4-2a1b-4f3c-8d4e-5f6a7b8c9d0e",
                "0192e8c4-2a1b-7f3c-cd4e-5f6a7b8c9d0e"
            })
    void rejectsAnythingElseWithoutEchoingIt(String text) {
        assertThatExceptionOfType(ValidationFailedException.class)
                .isThrownBy(() -> KairosId.parse("jobId", text))
                .satisfies(e -> {
                    assertThat(e.fieldErrors()).containsExactly(new FieldError("jobId", FieldError.FORMAT));
                    assertThat(e.getMessage()).isEqualTo(ValidationFailedException.DEFAULT_DETAIL);
                });
    }

    @Test
    void requireKairosIdRejectsOtherVersions() {
        UUID random = UUID.randomUUID();

        assertThat(KairosId.isKairosId(random)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> KairosId.requireKairosId(random));
        assertThatIllegalArgumentException().isThrownBy(() -> KairosId.shardOf(random));
        assertThatIllegalArgumentException().isThrownBy(() -> KairosId.timestampOf(random));
    }
}
