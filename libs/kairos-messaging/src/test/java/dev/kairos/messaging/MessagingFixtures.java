package dev.kairos.messaging;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Timestamp;
import dev.kairos.messaging.serde.MessageType;
import dev.kairos.messaging.topic.KairosTopic;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.CallOutcome;
import dev.kairos.proto.v1.Cancel;
import dev.kairos.proto.v1.CancelReason;
import dev.kairos.proto.v1.Completed;
import dev.kairos.proto.v1.Dead;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.RequestSnapshot;
import dev.kairos.proto.v1.Result;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Sample messages of every contract, with fresh ids so tests sharing the Kafka container never collide. */
public final class MessagingFixtures {

    /** A valid W3C trace parent. */
    public static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private static final Timestamp NOW =
            Timestamp.newBuilder().setSeconds(1_792_576_800L).build();

    private MessagingFixtures() {}

    /**
     * Returns a sample of a message type.
     *
     * @param type the type
     * @return a message of that type
     */
    public static MessageLite sample(MessageType<?> type) {
        if (type == MessageType.DISPATCH) {
            return dispatch();
        }
        if (type == MessageType.RESULT) {
            return result();
        }
        if (type == MessageType.CANCEL) {
            return cancel();
        }
        if (type == MessageType.COMPLETED) {
            return completed();
        }
        return dead();
    }

    /**
     * Returns the id a sample of the topic is keyed by.
     *
     * @param topic the topic
     * @param message a sample of the topic's message type
     * @return the key id
     */
    public static UUID keyOf(KairosTopic topic, MessageLite message) {
        ByteString id = switch (message) {
            case Dispatch dispatch -> dispatch.getTargetId();
            case Result result -> result.getRunId();
            case Cancel cancel -> cancel.getRunId();
            case Completed completed -> completed.getDefinitionId();
            case Dead dead -> dead.getDefinitionId();
            default -> throw new IllegalArgumentException("Unknown message for " + topic);
        };
        return ProtoIds.toUuid(id);
    }

    /**
     * Returns a dispatch.
     *
     * @return the message
     */
    public static Dispatch dispatch() {
        return Dispatch.newBuilder()
                .setRunId(id())
                .setShard(7)
                .setFence(1)
                .setAttempt(1)
                .setTeamId(id())
                .setDefinitionId(id())
                .setTargetId(id())
                .setEndpointId(id())
                .setPriority(9)
                .setScheduledAt(NOW)
                .setDueAt(NOW)
                .setRequest(RequestSnapshot.newBuilder()
                        .setBody(ByteString.copyFrom("{\"invoice\":42}", StandardCharsets.UTF_8)))
                .setTraceParent(TRACE_PARENT)
                .build();
    }

    /**
     * Returns a call result.
     *
     * @return the message
     */
    public static Result result() {
        return Result.newBuilder()
                .setRunId(id())
                .setShard(7)
                .setFence(1)
                .setAttempt(1)
                .setOutcome(CallOutcome.CALL_OUTCOME_SUCCEEDED)
                .setHttpStatus(200)
                .setLatencyMs(42)
                .setStartedAt(NOW)
                .setFinishedAt(NOW)
                .setExecutorId("executor-0")
                .build();
    }

    /**
     * Returns a cancel request.
     *
     * @return the message
     */
    public static Cancel cancel() {
        return Cancel.newBuilder()
                .setRunId(id())
                .setShard(7)
                .setFence(2)
                .setReason(CancelReason.CANCEL_REASON_REQUESTED)
                .setRequestedAt(NOW)
                .build();
    }

    /**
     * Returns a completion event.
     *
     * @return the message
     */
    public static Completed completed() {
        return Completed.newBuilder()
                .setRunId(id())
                .setShard(7)
                .setTeamId(id())
                .setDefinitionId(id())
                .setDefinitionVersion(3)
                .setTargetId(id())
                .setScheduledAt(NOW)
                .setHttpStatus(200)
                .setAttempts(1)
                .build();
    }

    /**
     * Returns a dead-letter event.
     *
     * @return the message
     */
    public static Dead dead() {
        return Dead.newBuilder()
                .setDlqId(id())
                .setRunId(id())
                .setShard(7)
                .setTeamId(id())
                .setDefinitionId(id())
                .setDefinitionVersion(3)
                .setTargetId(id())
                .setEndpointId(id())
                .setPriority(5)
                .setScheduledAt(NOW)
                .setAttempt(6)
                .setLastHttpStatus(503)
                .setDeadAt(NOW)
                .build();
    }

    private static ByteString id() {
        return ProtoIds.toBytes(UUID.randomUUID());
    }
}
