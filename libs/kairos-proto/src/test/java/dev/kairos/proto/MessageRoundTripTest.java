package dev.kairos.proto;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Value;
import dev.kairos.proto.v1.CallOutcome;
import dev.kairos.proto.v1.Cancel;
import dev.kairos.proto.v1.CancelReason;
import dev.kairos.proto.v1.Completed;
import dev.kairos.proto.v1.Dead;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.ErrorClass;
import dev.kairos.proto.v1.NameValue;
import dev.kairos.proto.v1.RequestSnapshot;
import dev.kairos.proto.v1.Result;
import dev.kairos.proto.v1.RunOutcome;
import dev.kairos.proto.v1.TargetType;
import dev.kairos.proto.v1.Trigger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every message survives a binary round trip with every field set. Each test first checks that the
 * sample sets every field, so a field added later without updating its sample fails here.
 */
class MessageRoundTripTest {

    private static final ByteString RUN_ID = ProtoIds.toBytes(UUID.fromString("0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d2a"));
    private static final ByteString TEAM_ID = ProtoIds.toBytes(UUID.fromString("0192e8c4-0000-7000-8000-000000000001"));
    private static final ByteString JOB_ID = ProtoIds.toBytes(UUID.fromString("0192e8c4-1111-7111-8111-11111111112a"));
    private static final ByteString ENDPOINT_ID =
            ProtoIds.toBytes(UUID.fromString("0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d0e"));
    private static final Instant SCHEDULED = Instant.parse("2026-10-20T02:00:00Z");

    @Test
    void dispatchRoundTrips() throws InvalidProtocolBufferException {
        RequestSnapshot request = RequestSnapshot.newBuilder()
                .putPathParams("customerId", "42")
                .addQuery(NameValue.newBuilder().setName("mode").setValue("sync"))
                .addHeaders(NameValue.newBuilder().setName("X-Source").setValue("kairos"))
                .setBody(ByteString.copyFrom("{\"mode\":\"sync\"}", StandardCharsets.UTF_8))
                .addSecretRefs("orders-api-token")
                .setSealedInputs(ByteString.copyFrom(new byte[] {1, 2, 3}))
                .build();
        Dispatch dispatch = Dispatch.newBuilder()
                .setRunId(RUN_ID)
                .setShard(42)
                .setFence(3)
                .setAttempt(2)
                .setInfraAttempt(1)
                .setTeamId(TEAM_ID)
                .setTargetType(TargetType.TARGET_TYPE_JOB)
                .setDefinitionId(JOB_ID)
                .setDefinitionVersion(4)
                .setTargetId(JOB_ID)
                .setTaskKey("charge")
                .setEndpointId(ENDPOINT_ID)
                .setEndpointVersion(7)
                .setPriority(9)
                .setTimeoutMs(10_000)
                .setScheduledAt(ProtoTimestamps.toTimestamp(SCHEDULED))
                .setDueAt(ProtoTimestamps.toTimestamp(SCHEDULED.plusMillis(250)))
                .setRequest(request)
                .setTraceParent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .build();

        assertEveryFieldSet(request);
        assertEveryFieldSet(request.getQuery(0));
        assertEveryFieldSet(dispatch);
        assertThat(Dispatch.parseFrom(dispatch.toByteArray())).isEqualTo(dispatch);
    }

    @Test
    void resultRoundTrips() throws InvalidProtocolBufferException {
        Result result = Result.newBuilder()
                .setRunId(RUN_ID)
                .setShard(42)
                .setFence(3)
                .setAttempt(2)
                .setInfraAttempt(1)
                .setOutcome(CallOutcome.CALL_OUTCOME_FAILED)
                .setErrorClass(ErrorClass.ERROR_CLASS_HTTP_5XX)
                .setHttpStatus(503)
                .setLatencyMs(87)
                .setStartedAt(ProtoTimestamps.toTimestamp(SCHEDULED))
                .setFinishedAt(ProtoTimestamps.toTimestamp(SCHEDULED.plusMillis(87)))
                .setExecutorId("kairos-executor-7f9c-1760925600000")
                .setError("Target answered 503")
                .setResponseSnippet("{\"error\":\"unavailable\"}")
                .putOutputs(
                        "orderId", Value.newBuilder().setStringValue("ord_123").build())
                .putOutputs("count", Value.newBuilder().setNumberValue(3).build())
                .setSealedOutputs(ByteString.copyFrom(new byte[] {9, 8, 7}))
                .setSentMarker(true)
                .setRetryAfterMs(30_000)
                .build();

        assertEveryFieldSet(result);
        assertThat(Result.parseFrom(result.toByteArray())).isEqualTo(result);
    }

    @Test
    void cancelRoundTrips() throws InvalidProtocolBufferException {
        Cancel cancel = Cancel.newBuilder()
                .setRunId(RUN_ID)
                .setShard(42)
                .setFence(3)
                .setReason(CancelReason.CANCEL_REASON_REQUESTED)
                .setRequestedAt(ProtoTimestamps.toTimestamp(SCHEDULED))
                .build();

        assertEveryFieldSet(cancel);
        assertThat(Cancel.parseFrom(cancel.toByteArray())).isEqualTo(cancel);
    }

    @Test
    void completedRoundTrips() throws InvalidProtocolBufferException {
        Completed completed = Completed.newBuilder()
                .setRunId(RUN_ID)
                .setShard(42)
                .setTeamId(TEAM_ID)
                .setTargetType(TargetType.TARGET_TYPE_WORKFLOW_TASK)
                .setDefinitionId(JOB_ID)
                .setDefinitionVersion(4)
                .setTargetId(RUN_ID)
                .setTaskKey("charge")
                .setTrigger(Trigger.TRIGGER_SCHEDULE)
                .setScheduledAt(ProtoTimestamps.toTimestamp(SCHEDULED))
                .setOutcome(RunOutcome.RUN_OUTCOME_DEAD_LETTERED)
                .setErrorClass(ErrorClass.ERROR_CLASS_TIMEOUT)
                .setHttpStatus(504)
                .setAttempts(6)
                .setStartedAt(ProtoTimestamps.toTimestamp(SCHEDULED.plusSeconds(1)))
                .setFinishedAt(ProtoTimestamps.toTimestamp(SCHEDULED.plusSeconds(900)))
                .setLatencyMs(10_000)
                .build();

        assertEveryFieldSet(completed);
        assertThat(Completed.parseFrom(completed.toByteArray())).isEqualTo(completed);
    }

    @Test
    void deadRoundTrips() throws InvalidProtocolBufferException {
        Dead dead = Dead.newBuilder()
                .setDlqId(ProtoIds.toBytes(UUID.fromString("0192e8c4-3333-7333-8333-33333333332a")))
                .setRunId(RUN_ID)
                .setShard(42)
                .setTeamId(TEAM_ID)
                .setTargetType(TargetType.TARGET_TYPE_JOB)
                .setDefinitionId(JOB_ID)
                .setDefinitionVersion(4)
                .setTargetId(JOB_ID)
                .setTaskKey("charge")
                .setEndpointId(ENDPOINT_ID)
                .setEndpointVersion(7)
                .setPriority(5)
                .setScheduledAt(ProtoTimestamps.toTimestamp(SCHEDULED))
                .setAttempt(6)
                .setInfraAttempt(1)
                .setReason(ErrorClass.ERROR_CLASS_HTTP_4XX)
                .setLastHttpStatus(400)
                .setDeadAt(ProtoTimestamps.toTimestamp(SCHEDULED.plusSeconds(900)))
                .build();

        assertEveryFieldSet(dead);
        assertThat(Dead.parseFrom(dead.toByteArray())).isEqualTo(dead);
    }

    private static void assertEveryFieldSet(Message message) {
        assertThat(message.getAllFields().keySet())
                .as("fields set on %s", message.getDescriptorForType().getName())
                .containsExactlyInAnyOrderElementsOf(
                        message.getDescriptorForType().getFields());
    }
}
