package dev.kairos.engine.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import dev.kairos.domain.id.JobId;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.model.OutboxTopic;
import dev.kairos.domain.shard.Shard;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Cancel;
import dev.kairos.proto.v1.CancelReason;
import dev.kairos.proto.v1.Completed;
import dev.kairos.proto.v1.Dead;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.testsupport.TestIds;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OutboxMessageTest {

    private static final Instant AVAILABLE = Instant.parse("2026-10-20T10:00:00.123456789Z");
    private static final String TRACE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final RunId runId = TestIds.runId(17);
    private final JobId jobId = TestIds.jobId(17);

    @ParameterizedTest
    @CsvSource({"10, RUN_HIGH", "8, RUN_HIGH", "7, RUN_MEDIUM", "5, RUN_MEDIUM", "4, RUN_LOW", "1, RUN_LOW"})
    void dispatchGoesToTheTopicOfItsPriorityTier(int priority, OutboxTopic topic) {
        OutboxMessage message = OutboxMessage.dispatch(dispatch(priority, TRACE), AVAILABLE);

        assertThat(message.topic()).isEqualTo(topic);
    }

    @Test
    void dispatchIsKeyedByTargetIdAndNamesItsRun() {
        Dispatch dispatch = dispatch(5, TRACE);

        OutboxMessage message = OutboxMessage.dispatch(dispatch, AVAILABLE);

        assertThat(message.shard()).isEqualTo(Shard.of(17));
        assertThat(message.key()).isEqualTo(ProtoIds.toBytes(jobId.value()));
        assertThat(message.runId()).isEqualTo(runId.value());
        assertThat(message.payload()).isEqualTo(dispatch.toByteString());
        assertThat(message.traceParent()).isEqualTo(TRACE);
        assertThat(message.availableAt()).isEqualTo(Instant.parse("2026-10-20T10:00:00.123456Z"));
    }

    @Test
    void dispatchWithoutTraceParentIsNotTraced() {
        assertThat(OutboxMessage.dispatch(dispatch(5, ""), AVAILABLE).traceParent())
                .isNull();
    }

    @Test
    void cancelIsKeyedByRunId() {
        Cancel cancel = Cancel.newBuilder()
                .setRunId(ProtoIds.toBytes(runId.value()))
                .setShard(17)
                .setFence(3)
                .setReason(CancelReason.CANCEL_REASON_REQUESTED)
                .build();

        OutboxMessage message = OutboxMessage.cancel(cancel, AVAILABLE, "malformed");

        assertThat(message.topic()).isEqualTo(OutboxTopic.RUN_CANCEL);
        assertThat(message.key()).isEqualTo(ProtoIds.toBytes(runId.value()));
        assertThat(message.runId()).isEqualTo(runId.value());
        assertThat(message.traceParent()).isNull();
    }

    @Test
    void completedAndDeadAreKeyedByDefinitionIdWithoutRun() {
        Completed completed = Completed.newBuilder()
                .setRunId(ProtoIds.toBytes(runId.value()))
                .setShard(17)
                .setDefinitionId(ProtoIds.toBytes(jobId.value()))
                .build();
        Dead dead = Dead.newBuilder()
                .setDlqId(ProtoIds.toBytes(TestIds.dlqId(17).value()))
                .setRunId(ProtoIds.toBytes(runId.value()))
                .setShard(17)
                .setDefinitionId(ProtoIds.toBytes(jobId.value()))
                .build();

        OutboxMessage completedRow = OutboxMessage.completed(completed, AVAILABLE, TRACE);
        OutboxMessage deadRow = OutboxMessage.dead(dead, AVAILABLE, null);

        assertThat(completedRow.topic()).isEqualTo(OutboxTopic.RUN_COMPLETED);
        assertThat(completedRow.key()).isEqualTo(ProtoIds.toBytes(jobId.value()));
        assertThat(completedRow.runId()).isNull();
        assertThat(completedRow.traceParent()).isEqualTo(TRACE);
        assertThat(deadRow.topic()).isEqualTo(OutboxTopic.RUN_DEAD);
        assertThat(deadRow.key()).isEqualTo(ProtoIds.toBytes(jobId.value()));
        assertThat(deadRow.runId()).isNull();
    }

    @Test
    void rejectsAMissingId() {
        Completed withoutDefinition = Completed.newBuilder().setShard(17).build();

        assertThatThrownBy(() -> OutboxMessage.completed(withoutDefinition, AVAILABLE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsKeysAndPayloadsOutsideTheTableLimits() {
        ByteString payload = ByteString.copyFromUtf8("payload");

        assertThatThrownBy(() -> row(OutboxTopic.RUN_DEAD, ByteString.EMPTY, payload, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> row(
                        OutboxTopic.RUN_DEAD,
                        ByteString.copyFrom(new byte[OutboxMessage.MAX_KEY_BYTES + 1]),
                        payload,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> row(
                        OutboxTopic.RUN_DEAD,
                        key(),
                        ByteString.copyFrom(new byte[OutboxMessage.MAX_PAYLOAD_BYTES + 1]),
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(row(
                                OutboxTopic.RUN_DEAD,
                                key(),
                                ByteString.copyFrom(new byte[OutboxMessage.MAX_PAYLOAD_BYTES]),
                                null,
                                null)
                        .payload()
                        .size())
                .isEqualTo(OutboxMessage.MAX_PAYLOAD_BYTES);
    }

    @Test
    void dispatchAndCancelRowsMustNameTheirRun() {
        ByteString payload = ByteString.copyFromUtf8("payload");

        assertThatThrownBy(() -> row(OutboxTopic.RUN_LOW, key(), payload, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RUN_LOW");
        assertThatThrownBy(() -> row(OutboxTopic.RUN_CANCEL, key(), payload, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsARunOfAnotherShard() {
        UUID otherShard = TestIds.runId(18).value();

        assertThatThrownBy(() -> row(OutboxTopic.RUN_HIGH, key(), ByteString.EMPTY, otherShard, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shard");
    }

    @Test
    void rejectsAMalformedTraceParent() {
        assertThatThrownBy(() -> row(OutboxTopic.RUN_HIGH, key(), ByteString.EMPTY, runId.value(), "00-bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceparent");
    }

    private Dispatch dispatch(int priority, String traceParent) {
        return Dispatch.newBuilder()
                .setRunId(ProtoIds.toBytes(runId.value()))
                .setShard(17)
                .setFence(1)
                .setTargetId(ProtoIds.toBytes(jobId.value()))
                .setPriority(priority)
                .setTraceParent(traceParent)
                .build();
    }

    private ByteString key() {
        return ProtoIds.toBytes(jobId.value());
    }

    private static OutboxMessage row(
            OutboxTopic topic, ByteString key, ByteString payload, UUID runId, String traceParent) {
        return new OutboxMessage(Shard.of(17), topic, key, payload, runId, AVAILABLE, traceParent);
    }
}
