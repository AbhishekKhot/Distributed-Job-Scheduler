package dev.kairos.domain.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EndpointSuspendRuleTest {

    private static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");
    private static final EndpointSuspendRule RULE = EndpointSuspendRule.DEFAULT;

    @Test
    void defaultsMatchTheLld() {
        assertThat(RULE.deadLetterThreshold()).isEqualTo(50);
        assertThat(RULE.window()).isEqualTo(Duration.ofMinutes(5));
        assertThat(RULE.failurePercent()).isEqualTo(80);
        assertThat(RULE.minAttempts()).isEqualTo(200);
        assertThat(RULE.coolDown()).isEqualTo(Duration.ofMinutes(10));
    }

    @ParameterizedTest(name = "attempts={0} successes={1} dead={2} -> {3}")
    @CsvSource({
        "0, 0, 0, KEEP",
        "1000, 1000, 49, KEEP",
        "1000, 1000, 50, DEAD_LETTERS",
        "10, 0, 50, DEAD_LETTERS",
        "199, 0, 0, KEEP",
        "200, 40, 0, FAILURE_RATE",
        "200, 41, 0, KEEP",
        "1000, 200, 0, FAILURE_RATE",
        "1000, 201, 0, KEEP",
        "1000, 0, 60, DEAD_LETTERS"
    })
    void suspendsOnDeadLettersOrFailureRate(int attempts, int successes, int deadLettered, String expected) {
        SuspendDecision decision =
                RULE.evaluate(new EndpointHealthWindow(attempts, successes, deadLettered), null, NOW);

        SuspendDecision wanted = expected.equals("KEEP")
                ? new SuspendDecision.Keep()
                : new SuspendDecision.Suspend(SuspendDecision.Reason.valueOf(expected));
        assertThat(decision).isEqualTo(wanted);
    }

    @Test
    void coolDownHoldsOffASuspensionAfterARecentStatusChange() {
        EndpointHealthWindow failing = new EndpointHealthWindow(300, 0, 0);
        Instant changed = NOW.minus(Duration.ofMinutes(4));

        assertThat(RULE.evaluate(failing, changed, NOW))
                .isEqualTo(new SuspendDecision.CoolingDown(
                        SuspendDecision.Reason.FAILURE_RATE, changed.plus(Duration.ofMinutes(10))));
        assertThat(RULE.evaluate(failing, NOW.minus(Duration.ofMinutes(10)), NOW))
                .isEqualTo(new SuspendDecision.Suspend(SuspendDecision.Reason.FAILURE_RATE));
        assertThat(RULE.evaluate(new EndpointHealthWindow(300, 300, 0), changed, NOW))
                .isEqualTo(new SuspendDecision.Keep());
    }

    @Test
    void validatesCountersAndParameters() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EndpointHealthWindow(-1, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new EndpointHealthWindow(0, -1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new EndpointHealthWindow(0, 0, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new EndpointHealthWindow(1, 2, 0));
        assertThat(new EndpointHealthWindow(10, 4, 0).failures()).isEqualTo(6);

        Duration five = Duration.ofMinutes(5);
        assertThatIllegalArgumentException().isThrownBy(() -> new EndpointSuspendRule(0, five, 80, 200, five));
        assertThatIllegalArgumentException().isThrownBy(() -> new EndpointSuspendRule(50, five, 80, 0, five));
        assertThatIllegalArgumentException().isThrownBy(() -> new EndpointSuspendRule(50, five, 0, 200, five));
        assertThatIllegalArgumentException().isThrownBy(() -> new EndpointSuspendRule(50, five, 101, 200, five));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EndpointSuspendRule(50, Duration.ZERO, 80, 200, five));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EndpointSuspendRule(50, five, 80, 200, Duration.ofSeconds(-1)));
    }
}
