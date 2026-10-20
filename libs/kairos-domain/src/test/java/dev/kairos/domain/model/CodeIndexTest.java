package dev.kairos.domain.model;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;

class CodeIndexTest {

    private enum Duplicated implements CodedEnum {
        FIRST,
        SECOND;

        @Override
        public int code() {
            return 1;
        }
    }

    private enum StartsAtZero implements CodedEnum {
        ONLY;

        @Override
        public int code() {
            return 0;
        }
    }

    @Test
    void rejectsDuplicateCodes() {
        assertThatIllegalStateException()
                .isThrownBy(() -> CodeIndex.of(Duplicated.values()))
                .withMessageContaining("twice");
    }

    @Test
    void rejectsCodesBelowOne() {
        assertThatIllegalStateException().isThrownBy(() -> CodeIndex.of(StartsAtZero.values()));
    }

    @Test
    void rejectsAnEmptyEnum() {
        assertThatIllegalArgumentException().isThrownBy(() -> CodeIndex.of(new Duplicated[0]));
    }
}
