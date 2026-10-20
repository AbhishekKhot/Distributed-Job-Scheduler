package dev.kairos.engine.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.model.RunState;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.run.Fence;
import dev.kairos.engine.EngineFixtures;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunFactoryTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    private final MutableClock clock = MutableClock.at(NOW);
    private final RunFactory factory = new RunFactory(TestIds.factory(clock), clock);
    private final RunTarget job = EngineFixtures.job(77, 9);

    @Test
    void scheduledRunWithinTheHorizonIsQueuedAtItsSpreadDueTime() {
        PlannedRun planned = factory.scheduled(job, NOW.plusSeconds(2), Duration.ofMillis(1_500));
        InflightRun run = planned.run();

        assertThat(run.state()).isEqualTo(RunState.QUEUED);
        assertThat(run.fence()).isEqualTo(new Fence(1));
        assertThat(run.scheduledAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(run.dueAt()).isEqualTo(NOW.plusMillis(3_500));
        assertThat(run.trigger()).isEqualTo(Trigger.SCHEDULE);
        assertThat(run.attempt()).isEqualTo(1);
        assertThat(run.seriesStart()).isEqualTo(1);
        assertThat(run.seriesStartedAt()).isEqualTo(NOW);
        assertThat(run.infraAttempt()).isZero();
        assertThat(run.shard().value()).isEqualTo(77);
        assertThat(run.definitionVersion()).isEqualTo(3);
        assertThat(run.endpointVersion()).isEqualTo(2);
        assertThat(run.priority().value()).isEqualTo(9);
        assertThat(planned.call()).isEqualTo(job.call());
    }

    @Test
    void scheduledRunDueExactlyAtTheHorizonIsQueuedAndLaterIsDelayed() {
        PlannedRun atHorizon = factory.scheduled(job, NOW.plusSeconds(5), Duration.ZERO);
        PlannedRun beyond = factory.scheduled(job, NOW.plusSeconds(5), Duration.ofMillis(1));

        assertThat(atHorizon.run().state()).isEqualTo(RunState.QUEUED);
        assertThat(beyond.run().state()).isEqualTo(RunState.DELAYED);
        assertThat(beyond.run().fence()).isEqualTo(Fence.INITIAL);
    }

    @Test
    void scheduledRunChecksTheSpread() {
        assertThatThrownBy(() -> factory.scheduled(job, NOW, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.scheduled(job, NOW, Duration.ofSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customHorizonControlsWhatIsDispatchedAtCreation() {
        RunFactory noLookAhead = new RunFactory(TestIds.factory(clock), clock, Duration.ZERO);

        assertThat(noLookAhead.scheduled(job, NOW, Duration.ZERO).run().state()).isEqualTo(RunState.QUEUED);
        assertThat(noLookAhead
                        .scheduled(job, NOW.plusMillis(1), Duration.ZERO)
                        .run()
                        .state())
                .isEqualTo(RunState.DELAYED);
        assertThatThrownBy(() -> new RunFactory(TestIds.factory(clock), clock, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void immediateAndManualRunsAreQueuedAndDueNow() {
        InflightRun immediate = factory.immediate(job, NOW).run();
        InflightRun manual = factory.manual(job, NOW.minusSeconds(30)).run();

        assertThat(immediate.state()).isEqualTo(RunState.QUEUED);
        assertThat(immediate.trigger()).isEqualTo(Trigger.IMMEDIATE);
        assertThat(immediate.dueAt()).isEqualTo(NOW);
        assertThat(manual.trigger()).isEqualTo(Trigger.MANUAL);
        assertThat(manual.scheduledAt()).isEqualTo(NOW.minusSeconds(30));
        assertThat(manual.dueAt()).isEqualTo(NOW);
        assertThat(manual.fence()).isEqualTo(new Fence(1));
        assertThat(immediate.runId()).isNotEqualTo(manual.runId());
    }

    @Test
    void backfillIsDelayedAndPacedFromNow() {
        List<Instant> occurrences = List.of(NOW.minusSeconds(300), NOW.minusSeconds(240), NOW.minusSeconds(180));

        List<PlannedRun> runs = factory.backfill(job, occurrences, 4);

        assertThat(runs).extracting(planned -> planned.run().state()).containsOnly(RunState.DELAYED);
        assertThat(runs).extracting(planned -> planned.run().trigger()).containsOnly(Trigger.BACKFILL);
        assertThat(runs).extracting(planned -> planned.run().scheduledAt()).containsExactlyElementsOf(occurrences);
        assertThat(runs)
                .extracting(planned -> planned.run().dueAt())
                .containsExactly(NOW, NOW.plusMillis(250), NOW.plusMillis(500));
        assertThat(runs).extracting(planned -> planned.run().fence()).containsOnly(Fence.INITIAL);
    }

    @Test
    void backfillChecksItsInput() {
        List<Instant> tooMany = new ArrayList<>();
        for (int i = 0; i <= RunFactory.MAX_BACKFILL_OCCURRENCES; i++) {
            tooMany.add(NOW.minusSeconds(i));
        }

        assertThatThrownBy(() -> factory.backfill(job, List.of(), 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.backfill(job, tooMany, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.backfill(job, Collections.nCopies(2, NOW), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
        assertThatThrownBy(() -> factory.backfill(job, List.of(NOW), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.backfill(job, List.of(NOW), RunFactory.MAX_BACKFILL_RATE_PER_SECOND + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redriveKeepsTheRunIdAndStartsANewSeries() {
        RunId original = TestIds.runId(77);
        Instant scheduledAt = NOW.minusSeconds(3_600);

        InflightRun run =
                factory.redrive(job, original, scheduledAt, 6, new Fence(14)).run();

        assertThat(run.runId()).isEqualTo(original);
        assertThat(run.scheduledAt()).isEqualTo(scheduledAt);
        assertThat(run.state()).isEqualTo(RunState.DELAYED);
        assertThat(run.dueAt()).isEqualTo(NOW);
        assertThat(run.attempt()).isEqualTo(7);
        assertThat(run.seriesStart()).isEqualTo(7);
        assertThat(run.seriesStartedAt()).isEqualTo(NOW);
        assertThat(run.fence()).isEqualTo(new Fence(14));
        assertThat(run.trigger()).isEqualTo(Trigger.REDRIVE);
    }

    @Test
    void redriveChecksTheRunAndAttempt() {
        assertThatThrownBy(() -> factory.redrive(job, TestIds.runId(78), NOW, 1, Fence.INITIAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.redrive(job, TestIds.runId(77), NOW, 0, Fence.INITIAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.redrive(job, TestIds.runId(77), NOW, Short.MAX_VALUE, Fence.INITIAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void waitingTaskKeepsItsUpstreamCount() {
        RunTarget task = EngineFixtures.task(77, "settle");

        InflightRun run = factory.waiting(task, NOW, Trigger.SCHEDULE, 2).run();

        assertThat(run.state()).isEqualTo(RunState.WAITING);
        assertThat(run.remainingDeps()).isEqualTo(2);
        assertThat(run.fence()).isEqualTo(Fence.INITIAL);
        assertThat(run.taskKeyColumn()).isEqualTo("settle");
        assertThat(run.targetId()).isEqualTo(task.targetId());
        assertThatThrownBy(() -> factory.waiting(task, NOW, Trigger.SCHEDULE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
