package dev.kairos.engine.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.model.RunState;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.run.Fence;
import dev.kairos.engine.EngineFixtures;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.ProtoTimestamps;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.TargetType;
import dev.kairos.testsupport.MutableClock;
import dev.kairos.testsupport.TestIds;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DispatchMessageBuilderTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private static final String TRACE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final MutableClock clock = MutableClock.at(NOW);
    private final RunFactory factory = new RunFactory(TestIds.factory(clock), clock);

    @Test
    void carriesTheRunThePinnedVersionsAndTheRequest() {
        RunTarget job = EngineFixtures.job(12, 8);
        PlannedRun planned = factory.immediate(job, NOW);
        InflightRun run = planned.run();

        Dispatch dispatch = DispatchMessageBuilder.build(run, planned.call(), TRACE);

        assertThat(ProtoIds.toUuid(dispatch.getRunId())).isEqualTo(run.runId().value());
        assertThat(dispatch.getShard()).isEqualTo(12);
        assertThat(dispatch.getFence()).isEqualTo(1);
        assertThat(dispatch.getAttempt()).isEqualTo(1);
        assertThat(dispatch.getInfraAttempt()).isZero();
        assertThat(ProtoIds.toUuid(dispatch.getTeamId())).isEqualTo(job.teamId().value());
        assertThat(dispatch.getTargetType()).isEqualTo(TargetType.TARGET_TYPE_JOB);
        assertThat(ProtoIds.toUuid(dispatch.getDefinitionId())).isEqualTo(job.definitionId());
        assertThat(dispatch.getDefinitionVersion()).isEqualTo(3);
        assertThat(ProtoIds.toUuid(dispatch.getTargetId())).isEqualTo(job.targetId());
        assertThat(dispatch.getTaskKey()).isEmpty();
        assertThat(ProtoIds.toUuid(dispatch.getEndpointId()))
                .isEqualTo(job.endpointId().value());
        assertThat(dispatch.getEndpointVersion()).isEqualTo(2);
        assertThat(dispatch.getPriority()).isEqualTo(8);
        assertThat(dispatch.getTimeoutMs()).isEqualTo(30_000);
        assertThat(ProtoTimestamps.toInstant(dispatch.getScheduledAt())).isEqualTo(NOW);
        assertThat(ProtoTimestamps.toInstant(dispatch.getDueAt())).isEqualTo(NOW);
        assertThat(dispatch.getRequest()).isEqualTo(job.call().request());
        assertThat(dispatch.getTraceParent()).isEqualTo(TRACE);
    }

    @Test
    void workflowTaskCarriesItsKeyAndDropsAMalformedTrace() {
        PlannedRun planned = factory.manual(EngineFixtures.task(200, "charge"), NOW);

        Dispatch dispatch = DispatchMessageBuilder.build(planned.run(), planned.call(), "not-a-trace");

        assertThat(dispatch.getTargetType()).isEqualTo(TargetType.TARGET_TYPE_WORKFLOW_TASK);
        assertThat(dispatch.getTaskKey()).isEqualTo("charge");
        assertThat(dispatch.getTraceParent()).isEmpty();
    }

    @Test
    void neverCarriesASecretValue() {
        PlannedRun planned = factory.immediate(EngineFixtures.job(12, 5), NOW);

        byte[] payload = DispatchMessageBuilder.build(planned.run(), planned.call(), null)
                .toByteArray();

        // The executor resolves the reference; only its name travels.
        assertThat(contains(payload, EngineFixtures.SECRET_REF)).isTrue();
        assertThat(contains(payload, EngineFixtures.SECRET_VALUE)).isFalse();
    }

    @Test
    void buildsOnlyQueuedRuns() {
        PlannedRun delayed = factory.scheduled(EngineFixtures.job(12, 5), NOW.plusSeconds(60), Duration.ZERO);

        assertThatThrownBy(() -> DispatchMessageBuilder.build(delayed.run(), delayed.call(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redispatchMovesTheRunToQueuedAndIncrementsTheFence() {
        PlannedRun delayed =
                factory.redrive(EngineFixtures.job(12, 5), TestIds.runId(12), NOW.minusSeconds(60), 3, new Fence(9));

        DispatchMessageBuilder.Redispatch redispatch =
                DispatchMessageBuilder.redispatch(delayed.run(), delayed.call(), NOW.plusSeconds(1), TRACE);

        assertThat(redispatch.run().state()).isEqualTo(RunState.QUEUED);
        assertThat(redispatch.run().fence()).isEqualTo(new Fence(10));
        assertThat(redispatch.run().dueAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(redispatch.run().attempt()).isEqualTo(4);
        assertThat(redispatch.run().trigger()).isEqualTo(Trigger.REDRIVE);
        assertThat(redispatch.message().getFence()).isEqualTo(10);
        assertThat(ProtoTimestamps.toInstant(redispatch.message().getDueAt())).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void redispatchOfAWaitingTaskClearsItsUpstreamCount() {
        PlannedRun waiting = factory.waiting(EngineFixtures.task(12, "ship"), NOW, Trigger.SCHEDULE, 1);

        DispatchMessageBuilder.Redispatch redispatch =
                DispatchMessageBuilder.redispatch(waiting.run(), waiting.call(), NOW, null);

        assertThat(redispatch.run().remainingDeps()).isZero();
        assertThat(redispatch.run().fence()).isEqualTo(new Fence(1));
    }

    @Test
    void redispatchFollowsTheStateMachine() {
        PlannedRun queued = factory.immediate(EngineFixtures.job(12, 5), NOW);

        DispatchMessageBuilder.Redispatch again =
                DispatchMessageBuilder.redispatch(queued.run(), queued.call(), NOW, null);

        assertThat(again.run().fence()).isEqualTo(new Fence(2));
        assertThatThrownBy(() -> new DispatchMessageBuilder.Redispatch(queued.run(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void redispatchRequeuesARunningRunWithANewFence() {
        InflightRun queued = factory.immediate(EngineFixtures.job(12, 5), NOW).run();
        InflightRun running = new InflightRun(
                queued.runId(),
                queued.teamId(),
                queued.targetType(),
                queued.definitionId(),
                queued.definitionVersion(),
                queued.targetId(),
                queued.taskKey(),
                queued.endpointId(),
                queued.endpointVersion(),
                queued.scheduledAt(),
                queued.dueAt(),
                RunState.RUNNING,
                1,
                1,
                NOW,
                0,
                new Fence(2),
                0,
                queued.priority(),
                queued.trigger(),
                null);

        // RUNNING -> QUEUED is the lease sweeper's re-queue and is allowed; it increments the fence.
        assertThat(DispatchMessageBuilder.redispatch(running, EngineFixtures.call(), NOW, null)
                        .run()
                        .fence())
                .isEqualTo(new Fence(3));
    }

    private static boolean contains(byte[] haystack, String needle) {
        byte[] bytes = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + bytes.length <= haystack.length; i++) {
            for (int j = 0; j < bytes.length; j++) {
                if (haystack[i + j] != bytes[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
