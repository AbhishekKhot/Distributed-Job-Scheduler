package dev.kairos.domain.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.kairos.domain.model.Outcome;
import dev.kairos.domain.model.RunState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RunStatusTest {

    @ParameterizedTest
    @EnumSource(RunState.class)
    void everyInflightStateHasAStatusThatMapsBack(RunState state) {
        RunStatus status = RunStatus.of(state);

        assertThat(status.name()).isEqualTo(state.name());
        assertThat(status.state()).contains(state);
        assertThat(status.outcome()).isEmpty();
        assertThat(status.isInflight()).isTrue();
        assertThat(status.isFinished()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = Outcome.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = {"FAILED_RETRYING", "EXECUTOR_LOST"})
    void everyFinalOutcomeHasAStatusThatMapsBack(Outcome outcome) {
        RunStatus status = RunStatus.of(outcome);

        assertThat(status.name()).isEqualTo(outcome.name());
        assertThat(status.outcome()).contains(outcome);
        assertThat(status.state()).isEmpty();
        assertThat(status.isFinished()).isTrue();
        assertThat(status.isInflight()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = Outcome.class,
            names = {"FAILED_RETRYING", "EXECUTOR_LOST"})
    void attemptOutcomesDoNotEndARun(Outcome outcome) {
        assertThatIllegalArgumentException().isThrownBy(() -> RunStatus.of(outcome));
    }

    @Test
    void fenceIsNeverNegativeAndDetectsOverflow() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Fence(-1));
        assertThatExceptionOfType(ArithmeticException.class).isThrownBy(() -> new Fence(Integer.MAX_VALUE).next());
        assertThat(new Fence(3).matches(new Fence(3))).isTrue();
        assertThat(new Fence(3).matches(new Fence(2))).isFalse();
    }
}
