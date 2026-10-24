package dev.kairos.watcher.outbox;

import com.google.protobuf.ByteString;
import dev.kairos.domain.model.OutboxTopic;
import dev.kairos.messaging.client.OutgoingRecord;
import dev.kairos.messaging.topic.KairosTopic;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A committed outbox row awaiting Kafka acknowledgement. */
public record OutboxRow(
        UUID id,
        OutboxTopic topic,
        ByteString key,
        ByteString payload,
        @Nullable String traceParent,
        Instant createdAt) {
    /** Validates values read from the cell database. */
    public OutboxRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Returns the exact stored key, payload and trace context for the Kafka publisher. */
    public OutgoingRecord record() {
        return new OutgoingRecord(KairosTopic.forOutbox(topic), key, payload, traceParent);
    }
}
