package dev.kairos.engine.outbox;

import com.google.protobuf.ByteString;
import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.model.OutboxTopic;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.shard.Shard;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Cancel;
import dev.kairos.proto.v1.Completed;
import dev.kairos.proto.v1.Dead;
import dev.kairos.proto.v1.Dispatch;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One message waiting in the {@code outbox} table (LLD section 2.3) to be published by the outbox relay.
 *
 * <p>The factories build the row from a Protobuf message and pick the topic and Kafka key of LLD section 4.2: a
 * dispatch goes to the topic of its priority tier keyed by target id, a cancel to {@code kairos.run.cancel} keyed by
 * run id, and completed and dead events to their topics keyed by definition (job or workflow) id. Payloads are the
 * serialized messages; they never carry secret values, because the messages themselves only hold secret references.
 *
 * <p>Times are truncated to microseconds, the precision of {@code timestamptz}, so a row reads back equal to what was
 * written.
 *
 * @param shard the shard of the run or definition; also the row's {@code shard_id} and the cell it is written to
 * @param topic the topic the relay publishes to
 * @param key the Kafka key, 1 to {@value #MAX_KEY_BYTES} bytes
 * @param payload the serialized message, at most {@value #MAX_PAYLOAD_BYTES} bytes
 * @param runId the run the message is about; required for dispatch and cancel rows, null for events
 * @param availableAt the time from which the relay may publish the row
 * @param traceParent the W3C trace context of the transaction that wrote the row, or null when not traced
 */
public record OutboxMessage(
        Shard shard,
        OutboxTopic topic,
        ByteString key,
        ByteString payload,
        @Nullable UUID runId,
        Instant availableAt,
        @Nullable String traceParent) {

    /** Largest Kafka key ({@code outbox_msg_key_ck}). */
    public static final int MAX_KEY_BYTES = 256;

    /** Largest payload ({@code outbox_payload_ck}). */
    public static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    /**
     * Validates the row against the {@code outbox} constraints, so a bad row fails here instead of in the database.
     *
     * @throws IllegalArgumentException if the key or payload size is out of range, a dispatch or cancel row has no run
     *     id, the run id is not in the row's shard, or the trace parent is malformed
     */
    public OutboxMessage {
        Objects.requireNonNull(shard, "shard");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(availableAt, "availableAt");
        if (key.isEmpty() || key.size() > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("An outbox key must be 1 to " + MAX_KEY_BYTES + " bytes");
        }
        if (payload.size() > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("An outbox payload must be at most " + MAX_PAYLOAD_BYTES + " bytes");
        }
        if (runId == null && namesRun(topic)) {
            throw new IllegalArgumentException("A " + topic + " row must name its run");
        }
        if (runId != null && !KairosId.shardOf(runId).equals(shard)) {
            throw new IllegalArgumentException("The run id belongs to another shard");
        }
        if (traceParent != null && !TraceParents.isValid(traceParent)) {
            throw new IllegalArgumentException("The trace parent is not a W3C traceparent");
        }
        availableAt = availableAt.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Builds the row of a dispatch: topic of the run's priority tier, keyed by target id, published from the run's due
     * time. The trace parent is taken from the message.
     *
     * @param dispatch the dispatch message
     * @param availableAt when the relay may publish it, normally the run's {@code due_at}
     * @return the row
     * @throws IllegalArgumentException if the message's ids, shard or priority are invalid
     */
    public static OutboxMessage dispatch(Dispatch dispatch, Instant availableAt) {
        Objects.requireNonNull(dispatch, "dispatch");
        OutboxTopic topic =
                OutboxTopic.dispatchTopic(Priority.of(dispatch.getPriority()).tier());
        return new OutboxMessage(
                Shard.of(dispatch.getShard()),
                topic,
                requireId(dispatch.getTargetId()),
                dispatch.toByteString(),
                ProtoIds.toUuid(dispatch.getRunId()),
                availableAt,
                TraceParents.orNull(dispatch.getTraceParent()));
    }

    /**
     * Builds the row of a cancel request, keyed by run id.
     *
     * @param cancel the cancel message
     * @param availableAt when the relay may publish it, normally now
     * @param traceParent the trace parent of the transaction, or null; a malformed value is dropped
     * @return the row
     * @throws IllegalArgumentException if the message's run id or shard is invalid
     */
    public static OutboxMessage cancel(Cancel cancel, Instant availableAt, @Nullable String traceParent) {
        Objects.requireNonNull(cancel, "cancel");
        return new OutboxMessage(
                Shard.of(cancel.getShard()),
                OutboxTopic.RUN_CANCEL,
                requireId(cancel.getRunId()),
                cancel.toByteString(),
                ProtoIds.toUuid(cancel.getRunId()),
                availableAt,
                TraceParents.orNull(traceParent));
    }

    /**
     * Builds the row of a completion event, keyed by definition id.
     *
     * @param completed the completion event
     * @param availableAt when the relay may publish it, normally now
     * @param traceParent the trace parent of the transaction, or null; a malformed value is dropped
     * @return the row
     * @throws IllegalArgumentException if the message's definition id or shard is invalid
     */
    public static OutboxMessage completed(Completed completed, Instant availableAt, @Nullable String traceParent) {
        Objects.requireNonNull(completed, "completed");
        return new OutboxMessage(
                Shard.of(completed.getShard()),
                OutboxTopic.RUN_COMPLETED,
                requireId(completed.getDefinitionId()),
                completed.toByteString(),
                null,
                availableAt,
                TraceParents.orNull(traceParent));
    }

    /**
     * Builds the row of a dead-letter event, keyed by definition id.
     *
     * @param dead the dead-letter event
     * @param availableAt when the relay may publish it, normally now
     * @param traceParent the trace parent of the transaction, or null; a malformed value is dropped
     * @return the row
     * @throws IllegalArgumentException if the message's definition id or shard is invalid
     */
    public static OutboxMessage dead(Dead dead, Instant availableAt, @Nullable String traceParent) {
        Objects.requireNonNull(dead, "dead");
        return new OutboxMessage(
                Shard.of(dead.getShard()),
                OutboxTopic.RUN_DEAD,
                requireId(dead.getDefinitionId()),
                dead.toByteString(),
                null,
                availableAt,
                TraceParents.orNull(traceParent));
    }

    private static boolean namesRun(OutboxTopic topic) {
        // outbox_run_id_ck: dispatch and cancel rows always name their run.
        return switch (topic) {
            case RUN_HIGH, RUN_MEDIUM, RUN_LOW, RUN_CANCEL -> true;
            case RUN_COMPLETED, RUN_DEAD -> false;
        };
    }

    private static ByteString requireId(ByteString id) {
        ProtoIds.toUuid(id);
        return id;
    }
}
