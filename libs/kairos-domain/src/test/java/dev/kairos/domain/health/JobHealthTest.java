package dev.kairos.domain.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.Outcome;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class JobHealthTest {

    private static final Instant T0 = Instant.parse("2026-10-20T09:00:00Z");

    private static JobHealth replay(String outcomes) {
        JobHealth health = JobHealth.EMPTY;
        for (int i = 0; i < outcomes.length(); i++) {
            Outcome outcome = outcomes.charAt(i) == 'D' ? Outcome.DEAD_LETTERED : Outcome.SUCCEEDED;
            health = health.withOutcome(outcome, T0.plusSeconds(i));
        }
        return health;
    }

    @Test
    void recordsOutcomesNewestFirstInTheMask() {
        JobHealth health = replay("SDD");

        assertThat(health.recentMask()).isEqualTo(0b011);
        assertThat(health.recentCount()).isEqualTo(3);
        assertThat(health.recentFailures()).isEqualTo(2);
        assertThat(health.consecutiveDead()).isEqualTo(2);
    }

    @Test
    void keepsOnlyTheLastTenOutcomes() {
        JobHealth health = replay("DDDDDSSSSSSSSSS");

        assertThat(health.recentCount()).isEqualTo(10);
        assertThat(health.recentMask()).isZero();
        assertThat(health.consecutiveDead()).isZero();
    }

    @Test
    void successResetsTheDeadLetterStreak() {
        assertThat(replay("DDDS").consecutiveDead()).isZero();
        assertThat(replay("DDSDD").consecutiveDead()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(
            value = Outcome.class,
            names = {"CANCELLED", "SKIPPED", "UPSTREAM_FAILED", "FAILED_RETRYING", "EXECUTOR_LOST"})
    void outcomesThatSayNothingAboutTheTargetAreIgnored(Outcome outcome) {
        JobHealth health = replay("DD");

        assertThat(health.withOutcome(outcome, T0)).isSameAs(health);
    }

    @ParameterizedTest
    @CsvSource({"DDDDD, false", "DDDDDD, true", "SSSSDDDDDD, true", "DDDDDSSSSS, false", "SDSDSDSDSDS, false"})
    void unhealthyWhenMostOfTheLastTenFailed(String outcomes, boolean unhealthy) {
        assertThat(replay(outcomes).isUnhealthy()).isEqualTo(unhealthy);
    }

    @Test
    void unhealthySinceIsSetOnceAndClearedOnRecovery() {
        JobHealth sick = replay("DDDDDD");
        JobHealth stillSick = sick.withOutcome(Outcome.DEAD_LETTERED, T0.plusSeconds(100));
        JobHealth recovered = replay("DDDDDDSSSSS");

        assertThat(sick.unhealthySince()).isEqualTo(T0.plusSeconds(5));
        assertThat(stillSick.unhealthySince()).isEqualTo(T0.plusSeconds(5));
        assertThat(recovered.unhealthySince()).isNull();
    }

    @Test
    void resumeRestartsTheStreakButKeepsTheHistory() {
        JobHealth health = replay("SDDD").afterResume();

        assertThat(health.consecutiveDead()).isZero();
        assertThat(health.recentMask()).isEqualTo(0b0111);
        assertThat(health.recentCount()).isEqualTo(4);
    }

    @Test
    void streakSaturatesAtTheSmallintRange() {
        JobHealth health = new JobHealth(0b1111111111, 10, Short.MAX_VALUE, T0);

        assertThat(health.withOutcome(Outcome.DEAD_LETTERED, T0).consecutiveDead())
                .isEqualTo(Short.MAX_VALUE);
    }

    @Test
    void rejectsInconsistentRows() {
        assertThatIllegalArgumentException().isThrownBy(() -> new JobHealth(0, -1, 0, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new JobHealth(0, 11, 0, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new JobHealth(0b100, 2, 0, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new JobHealth(0, 0, -1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new JobHealth(0, 0, Short.MAX_VALUE + 1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new JobHealth(0, 1, 0, T0));
        assertThatIllegalArgumentException().isThrownBy(() -> new JobHealth(0b111111, 6, 6, null));
    }

    @Property
    void maskAlwaysMatchesTheLastTenOutcomes(@ForAll @Size(max = 40) List<Boolean> deadLettered) {
        JobHealth health = JobHealth.EMPTY;
        for (boolean dead : deadLettered) {
            health = health.withOutcome(dead ? Outcome.DEAD_LETTERED : Outcome.SUCCEEDED, T0);
        }
        List<Boolean> lastTen = deadLettered.subList(Math.max(0, deadLettered.size() - 10), deadLettered.size());
        int streak = 0;
        for (int i = deadLettered.size() - 1; i >= 0 && deadLettered.get(i); i--) {
            streak++;
        }

        assertThat(health.recentCount()).isEqualTo(lastTen.size());
        assertThat(health.recentFailures())
                .isEqualTo((int) lastTen.stream().filter(b -> b).count());
        assertThat(health.consecutiveDead()).isEqualTo(streak);
        assertThat(health.isUnhealthy()).isEqualTo(health.recentFailures() >= JobHealth.UNHEALTHY_FAILURES);
    }

    @Test
    void autoPauseAfterThreeConsecutiveDeadLettersByDefault() {
        AutoPause autoPause = AutoPause.DEFAULT;
        JobHealth two = replay("DD");
        JobHealth three = replay("DDD");

        assertThat(autoPause.after()).isEqualTo(3);
        assertThat(autoPause.shouldPause(Outcome.DEAD_LETTERED, two)).isFalse();
        assertThat(autoPause.shouldPause(Outcome.DEAD_LETTERED, three)).isTrue();
        assertThat(autoPause.shouldPause(Outcome.CANCELLED, three)).isFalse();
        assertThat(autoPause.shouldPause(Outcome.DEAD_LETTERED, three.afterResume()))
                .isFalse();
        assertThat(autoPause.shouldPause(Outcome.DEAD_LETTERED, replay("DDDSD")))
                .isFalse();
    }

    @Test
    void zeroDisablesAutoPause() {
        assertThat(AutoPause.DISABLED.isEnabled()).isFalse();
        assertThat(AutoPause.DISABLED.shouldPause(Outcome.DEAD_LETTERED, replay("DDDDDDDDDD")))
                .isFalse();
        assertThat(new AutoPause(1).shouldPause(Outcome.DEAD_LETTERED, replay("D")))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101})
    void autoPauseThresholdIsValidated(int after) {
        assertThatExceptionOfType(ValidationFailedException.class)
                .isThrownBy(() -> new AutoPause(after))
                .satisfies(e -> assertThat(e.fieldErrors())
                        .containsExactly(new FieldError("autoPauseAfter", FieldError.RANGE)));
    }
}
