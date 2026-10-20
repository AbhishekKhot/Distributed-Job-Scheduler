package dev.kairos.domain.run;

import static dev.kairos.domain.run.RunStatus.CANCELLED;
import static dev.kairos.domain.run.RunStatus.DEAD_LETTERED;
import static dev.kairos.domain.run.RunStatus.DELAYED;
import static dev.kairos.domain.run.RunStatus.QUEUED;
import static dev.kairos.domain.run.RunStatus.RUNNING;
import static dev.kairos.domain.run.RunStatus.SKIPPED;
import static dev.kairos.domain.run.RunStatus.SUCCEEDED;
import static dev.kairos.domain.run.RunStatus.UPSTREAM_FAILED;
import static dev.kairos.domain.run.RunStatus.WAITING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class RunStateMachineTest {

    /** The expected table, written out independently of the implementation. */
    private static final Map<RunStatus, Set<RunStatus>> EXPECTED = Map.of(
            WAITING, EnumSet.of(QUEUED, DELAYED, UPSTREAM_FAILED, CANCELLED),
            QUEUED, EnumSet.of(QUEUED, RUNNING, DELAYED, CANCELLED, SKIPPED),
            RUNNING, EnumSet.of(SUCCEEDED, DEAD_LETTERED, DELAYED, QUEUED, CANCELLED, SKIPPED),
            DELAYED, EnumSet.of(QUEUED, CANCELLED),
            SUCCEEDED, EnumSet.noneOf(RunStatus.class),
            DEAD_LETTERED, EnumSet.of(DELAYED, QUEUED),
            CANCELLED, EnumSet.noneOf(RunStatus.class),
            SKIPPED, EnumSet.noneOf(RunStatus.class),
            UPSTREAM_FAILED, EnumSet.of(WAITING));

    static Stream<Arguments> allPairs() {
        return Arrays.stream(RunStatus.values())
                .flatMap(from -> Arrays.stream(RunStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void allowsExactlyTheTransitionsOfTheTable(RunStatus from, RunStatus to) {
        boolean expected = EXPECTED.get(from).contains(to);
        Fence fence = new Fence(4);

        assertThat(RunStateMachine.isAllowed(from, to)).isEqualTo(expected);
        if (expected) {
            RunTransition transition = RunStateMachine.transition(from, to, fence);
            assertThat(transition.from()).isEqualTo(from);
            assertThat(transition.to()).isEqualTo(to);
            assertThat(transition.fence()).isEqualTo(to == QUEUED || to == RUNNING ? new Fence(5) : fence);
            assertThat(transition.startsNewSeries()).isEqualTo(from.isFinished());
        } else {
            assertThatExceptionOfType(IllegalRunTransitionException.class)
                    .isThrownBy(() -> RunStateMachine.transition(from, to, fence))
                    .satisfies(e -> {
                        assertThat(e.from()).isEqualTo(from);
                        assertThat(e.to()).isEqualTo(to);
                        assertThat(e).hasMessage("A run cannot move from " + from + " to " + to);
                    });
        }
    }

    @ParameterizedTest
    @EnumSource(RunStatus.class)
    void allowedTargetsMatchTheTableAndCannotBeModified(RunStatus from) {
        Set<RunStatus> targets = RunStateMachine.allowedTargets(from);

        assertThat(targets).containsExactlyInAnyOrderElementsOf(EXPECTED.get(from));
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> targets.add(WAITING));
    }

    @Test
    void successCancelAndSkipAreAbsorbing() {
        assertThat(RunStateMachine.allowedTargets(SUCCEEDED)).isEmpty();
        assertThat(RunStateMachine.allowedTargets(CANCELLED)).isEmpty();
        assertThat(RunStateMachine.allowedTargets(SKIPPED)).isEmpty();
    }

    @Test
    void everyDispatchAndClaimIncrementsTheFence() {
        Fence created = RunStateMachine.initialFence(QUEUED);
        RunTransition claim = RunStateMachine.transition(QUEUED, RUNNING, created);
        RunTransition retry = RunStateMachine.transition(RUNNING, DELAYED, claim.fence());
        RunTransition redispatch = RunStateMachine.transition(DELAYED, QUEUED, retry.fence());
        RunTransition sweeperRedispatch = RunStateMachine.transition(QUEUED, QUEUED, redispatch.fence());
        RunTransition secondClaim = RunStateMachine.transition(QUEUED, RUNNING, sweeperRedispatch.fence());

        assertThat(created).isEqualTo(new Fence(1));
        assertThat(claim.fence()).isEqualTo(new Fence(2));
        assertThat(retry.fence()).isEqualTo(new Fence(2));
        assertThat(redispatch.fence()).isEqualTo(new Fence(3));
        assertThat(sweeperRedispatch.fence()).isEqualTo(new Fence(4));
        assertThat(secondClaim.fence()).isEqualTo(new Fence(5));
        // The first dispatch message carried fence 1: it can no longer claim the run.
        assertThat(secondClaim.fence().matches(created)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(RunStatus.class)
    void onlyQueuedAndRunningIncrementTheFence(RunStatus to) {
        assertThat(RunStateMachine.incrementsFence(to)).isEqualTo(to == QUEUED || to == RUNNING);
    }

    @Test
    void runsAreCreatedWaitingQueuedOrDelayed() {
        assertThat(RunStateMachine.initialFence(WAITING)).isEqualTo(Fence.INITIAL);
        assertThat(RunStateMachine.initialFence(DELAYED)).isEqualTo(Fence.INITIAL);
        assertThat(RunStateMachine.initialFence(QUEUED)).isEqualTo(Fence.INITIAL.next());
    }

    @ParameterizedTest
    @EnumSource(
            value = RunStatus.class,
            names = {"RUNNING", "SUCCEEDED", "DEAD_LETTERED", "CANCELLED", "SKIPPED", "UPSTREAM_FAILED"})
    void runsCannotBeCreatedInOtherStatuses(RunStatus initial) {
        assertThatIllegalArgumentException().isThrownBy(() -> RunStateMachine.initialFence(initial));
    }

    @Test
    void reopeningAFinishedRunStartsANewSeries() {
        assertThat(RunStateMachine.transition(DEAD_LETTERED, DELAYED, new Fence(3))
                        .startsNewSeries())
                .isTrue();
        assertThat(RunStateMachine.transition(UPSTREAM_FAILED, WAITING, Fence.INITIAL)
                        .startsNewSeries())
                .isTrue();
        assertThat(RunStateMachine.transition(RUNNING, DELAYED, new Fence(3)).startsNewSeries())
                .isFalse();
    }
}
