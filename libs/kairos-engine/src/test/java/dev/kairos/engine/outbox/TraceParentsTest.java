package dev.kairos.engine.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TraceParentsTest {

    private static final String VALID = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @ParameterizedTest
    @ValueSource(strings = {VALID, "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00"})
    void acceptsWellFormedValues(String value) {
        assertThat(TraceParents.isValid(value)).isTrue();
        assertThat(TraceParents.orNull(value)).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
                "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra",
                "00-4bf92f3577b34da6a3ce929d0e0e4736_00f067aa0ba902b7-01",
                "not a trace parent"
            })
    void rejectsMalformedValues(String value) {
        assertThat(TraceParents.isValid(value)).isFalse();
        assertThat(TraceParents.orNull(value)).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void treatsMissingValuesAsNotTraced(String value) {
        assertThat(TraceParents.orNull(value)).isNull();
    }
}
