package dev.kairos.engine.run;

import dev.kairos.domain.model.RunState;
import dev.kairos.domain.run.RunStateMachine;
import dev.kairos.domain.run.RunStatus;
import dev.kairos.domain.run.RunTransition;
import dev.kairos.engine.outbox.TraceParents;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.ProtoTimestamps;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.TargetType;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Builds the Protobuf {@link Dispatch} message of a run (LLD section 4.2) from its row and pinned versions.
 *
 * <p>The message carries ids, the pinned definition and endpoint versions, the fence and the rendered request; it
 * never carries a secret value, only the names of the secrets the executor resolves ({@link CallSpec}). Dispatching
 * increments the fence (HLD section 6): {@link #build} serialises a run that was created {@code QUEUED} (its fence was
 * already incremented at creation), and {@link #redispatch} moves a waiting, delayed, queued or running run to
 * {@code QUEUED} through the run state machine and returns the row with its new fence together with the message.
 *
 * <p>Stateless and thread-safe.
 */
public final class DispatchMessageBuilder {

    private DispatchMessageBuilder() {}

    /**
     * A run moved to {@code QUEUED} and the message that dispatches it.
     *
     * @param run the row to store: {@code QUEUED}, with the incremented fence and the new due time
     * @param message the dispatch message carrying that fence
     */
    public record Redispatch(InflightRun run, Dispatch message) {

        /** Validates that both parts are present. */
        public Redispatch {
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * Builds the dispatch of a run that is already {@code QUEUED}, with the fence it has.
     *
     * @param run the queued run
     * @param call the call timeout and rendered request
     * @param traceParent the trace parent of the transaction, or null; a malformed value is dropped
     * @return the message
     * @throws IllegalArgumentException if the run is not {@code QUEUED}
     */
    public static Dispatch build(InflightRun run, CallSpec call, @Nullable String traceParent) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(call, "call");
        if (run.state() != RunState.QUEUED) {
            throw new IllegalArgumentException("Only a QUEUED run is dispatched; use redispatch to move it first");
        }
        String trace = TraceParents.orNull(traceParent);
        Dispatch.Builder message = Dispatch.newBuilder()
                .setRunId(ProtoIds.toBytes(run.runId().value()))
                .setShard(run.shard().value())
                .setFence(run.fence().value())
                .setAttempt(run.attempt())
                .setInfraAttempt(run.infraAttempt())
                .setTeamId(ProtoIds.toBytes(run.teamId().value()))
                .setTargetType(protoTargetType(run.targetType()))
                .setDefinitionId(ProtoIds.toBytes(run.definitionId()))
                .setDefinitionVersion(run.definitionVersion())
                .setTargetId(ProtoIds.toBytes(run.targetId()))
                .setTaskKey(run.taskKeyColumn())
                .setEndpointId(ProtoIds.toBytes(run.endpointId().value()))
                .setEndpointVersion(run.endpointVersion())
                .setPriority(run.priority().value())
                .setTimeoutMs(call.timeoutMs())
                .setScheduledAt(ProtoTimestamps.toTimestamp(run.scheduledAt()))
                .setDueAt(ProtoTimestamps.toTimestamp(run.dueAt()))
                .setRequest(call.request());
        if (trace != null) {
            message.setTraceParent(trace);
        }
        return message.build();
    }

    /**
     * Moves a run to {@code QUEUED} and builds its dispatch: the delayed poller's due retries and deferrals, a workflow
     * task whose last upstream task finished, the stuck-queued sweeper's re-dispatch and the lease sweeper's re-queue.
     *
     * @param run the run as stored
     * @param call the call timeout and rendered request
     * @param dueAt the dispatch time to store
     * @param traceParent the trace parent of the transaction, or null; a malformed value is dropped
     * @return the queued row with its incremented fence, and the message
     * @throws dev.kairos.domain.run.IllegalRunTransitionException if the state machine does not allow the move
     */
    public static Redispatch redispatch(InflightRun run, CallSpec call, Instant dueAt, @Nullable String traceParent) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(dueAt, "dueAt");
        RunTransition transition = RunStateMachine.transition(run.status(), RunStatus.QUEUED, run.fence());
        InflightRun queued = run.queued(transition.fence(), dueAt);
        return new Redispatch(queued, build(queued, call, traceParent));
    }

    private static TargetType protoTargetType(dev.kairos.domain.model.TargetType targetType) {
        // Proto enum numbers equal the database codes (LLD section 4.2).
        return switch (targetType) {
            case JOB -> TargetType.TARGET_TYPE_JOB;
            case WORKFLOW_TASK -> TargetType.TARGET_TYPE_WORKFLOW_TASK;
            case WORKFLOW -> TargetType.TARGET_TYPE_WORKFLOW;
        };
    }
}
