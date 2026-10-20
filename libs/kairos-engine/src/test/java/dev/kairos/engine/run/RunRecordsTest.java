package dev.kairos.engine.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.JobId;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.RunState;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.run.Fence;
import dev.kairos.domain.run.RunStatus;
import dev.kairos.engine.EngineFixtures;
import dev.kairos.proto.v1.NameValue;
import dev.kairos.proto.v1.RequestSnapshot;
import dev.kairos.testsupport.TestIds;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Validation of the run records against the {@code run_inflight} constraints. */
class RunRecordsTest {

    private static final Instant AT = Instant.parse("2026-10-20T10:00:00.123456789Z");

    private final TeamId team = TestIds.teamId();
    private final EndpointId endpoint = TestIds.endpointId();
    private final JobId job = TestIds.jobId(42);

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "proxy-authorization", "COOKIE", "Set-Cookie"})
    void callSpecRejectsCredentialHeaders(String header) {
        RequestSnapshot request = RequestSnapshot.newBuilder()
                .addHeaders(NameValue.newBuilder().setName(header).setValue("Bearer value-that-must-not-leak"))
                .build();

        assertThatThrownBy(() -> new CallSpec(30_000, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("value-that-must-not-leak");
    }

    @Test
    void callSpecChecksTheTimeoutRange() {
        RequestSnapshot request = RequestSnapshot.getDefaultInstance();

        assertThat(new CallSpec(CallSpec.MIN_TIMEOUT_MS, request).timeoutMs()).isEqualTo(1_000);
        assertThat(new CallSpec(CallSpec.MAX_TIMEOUT_MS, request).timeoutMs()).isEqualTo(300_000);
        assertThatThrownBy(() -> new CallSpec(999, request)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallSpec(300_001, request)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jobTargetHasNoTaskKeyAndTargetsItsOwnJob() {
        RunTarget target = target(TargetType.JOB, job.value(), job.value(), null, Duration.ofSeconds(90));

        assertThat(target.shard().value()).isEqualTo(42);
        assertThat(target.maxLatenessMs()).isEqualTo(90_000);
        assertThatThrownBy(() -> target(TargetType.JOB, job.value(), job.value(), TaskKey.of("step"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        target(TargetType.JOB, job.value(), TestIds.jobId(42).value(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workflowTaskNeedsAKeyAndOneShard() {
        UUID workflow = TestIds.workflowId(42).value();
        UUID workflowRun = TestIds.workflowRunId(42).value();

        RunTarget task = target(TargetType.WORKFLOW_TASK, workflow, workflowRun, TaskKey.of("charge"), null);

        assertThat(task.maxLatenessMs()).isNull();
        assertThatThrownBy(() -> target(TargetType.WORKFLOW_TASK, workflow, workflowRun, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> target(
                        TargetType.WORKFLOW_TASK,
                        workflow,
                        TestIds.workflowRunId(43).value(),
                        TaskKey.of("charge"),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shards");
        assertThatThrownBy(() -> target(TargetType.WORKFLOW, workflow, workflow, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void targetChecksVersionsAndLateness() {
        assertThatThrownBy(() -> new RunTarget(
                        team,
                        TargetType.JOB,
                        job.value(),
                        0,
                        job.value(),
                        null,
                        endpoint,
                        1,
                        Priority.of(5),
                        null,
                        EngineFixtures.call()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> target(TargetType.JOB, job.value(), job.value(), null, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> target(TargetType.JOB, job.value(), job.value(), null, Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inflightRunTruncatesTimesToMicroseconds() {
        InflightRun run = run(RunState.QUEUED, 1, 1, 0, 0);

        assertThat(run.scheduledAt()).isEqualTo(Instant.parse("2026-10-20T10:00:00.123456Z"));
        assertThat(run.dueAt()).isEqualTo(run.scheduledAt());
        assertThat(run.seriesStartedAt()).isEqualTo(run.scheduledAt());
        assertThat(run.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(run.taskKeyColumn()).isEmpty();
        assertThat(run.shard().value()).isEqualTo(42);
    }

    @Test
    void inflightRunChecksCountersAndSeries() {
        assertThat(run(RunState.DELAYED, 4, 2, 3, 0).attempt()).isEqualTo(4);
        assertThatThrownBy(() -> run(RunState.QUEUED, 0, 1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(RunState.QUEUED, 2, 3, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(RunState.QUEUED, 2, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(RunState.QUEUED, 1, 1, 4, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(RunState.QUEUED, 1, 1, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(RunState.QUEUED, 40_000, 1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyAWaitingRunHasUpstreamTasks() {
        assertThat(run(RunState.WAITING, 1, 1, 0, 2).remainingDeps()).isEqualTo(2);
        assertThatThrownBy(() -> run(RunState.WAITING, 1, 1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(RunState.QUEUED, 1, 1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(RunState.QUEUED, 1, 1, 0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inflightRunBelongsToTheShardOfItsTarget() {
        assertThatThrownBy(() -> new InflightRun(
                        TestIds.runId(43),
                        team,
                        TargetType.JOB,
                        job.value(),
                        1,
                        job.value(),
                        null,
                        endpoint,
                        1,
                        AT,
                        AT,
                        RunState.QUEUED,
                        1,
                        1,
                        AT,
                        0,
                        Fence.INITIAL.next(),
                        0,
                        Priority.of(5),
                        Trigger.MANUAL,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shard");
        assertThatThrownBy(() -> new InflightRun(
                        TestIds.runId(42),
                        team,
                        TargetType.JOB,
                        job.value(),
                        1,
                        job.value(),
                        null,
                        endpoint,
                        1,
                        AT,
                        AT,
                        RunState.QUEUED,
                        1,
                        1,
                        AT,
                        0,
                        Fence.INITIAL.next(),
                        0,
                        Priority.of(5),
                        Trigger.MANUAL,
                        -5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plannedRunAndCreationRequireTheirParts() {
        InflightRun run = run(RunState.QUEUED, 1, 1, 0, 0);
        RunCreation creation = new RunCreation(new ArrayList<>(List.of(run)), List.of());

        assertThat(new PlannedRun(run, EngineFixtures.call()).run()).isEqualTo(run);
        assertThat(creation.created()).containsExactly(run);
        assertThatThrownBy(() -> creation.created().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new PlannedRun(run, null)).isInstanceOf(NullPointerException.class);
    }

    private RunTarget target(TargetType type, UUID definition, UUID targetId, TaskKey taskKey, Duration maxLateness) {
        return new RunTarget(
                team,
                type,
                definition,
                1,
                targetId,
                taskKey,
                endpoint,
                1,
                Priority.of(5),
                maxLateness,
                EngineFixtures.call());
    }

    private InflightRun run(RunState state, int attempt, int seriesStart, int infraAttempt, int remainingDeps) {
        RunId runId = TestIds.runId(42);
        return new InflightRun(
                runId,
                team,
                TargetType.JOB,
                job.value(),
                1,
                job.value(),
                null,
                endpoint,
                1,
                AT,
                AT,
                state,
                attempt,
                seriesStart,
                AT,
                infraAttempt,
                Fence.INITIAL,
                remainingDeps,
                Priority.of(5),
                Trigger.SCHEDULE,
                null);
    }
}
