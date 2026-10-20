package dev.kairos.messaging.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import dev.kairos.messaging.serde.MessageType;
import dev.kairos.messaging.topic.KairosTopic;
import dev.kairos.messaging.topic.KeyType;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One record to publish: topic, key, encoded message and the W3C trace parent that goes into the trace context header
 * ({@link dev.kairos.messaging.trace.TraceHeaders#TRACEPARENT}).
 *
 * @param topic the topic
 * @param key the record key, 1 to {@value #MAX_KEY_BYTES} bytes (16-byte ids in practice)
 * @param value the encoded message, 1 to {@link MessageType#MAX_PAYLOAD_BYTES} bytes
 * @param traceParent the trace parent, or null; a malformed value is dropped when the record is sent
 */
public record OutgoingRecord(
        KairosTopic topic,
        ByteString key,
        ByteString value,
        @Nullable String traceParent) {

    /** Largest key, the {@code outbox.msg_key} limit. */
    public static final int MAX_KEY_BYTES = 256;

    /**
     * Validates the sizes.
     *
     * @throws IllegalArgumentException if the key or value is empty or too large
     */
    public OutgoingRecord {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (key.isEmpty() || key.size() > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("A record key must be 1 to " + MAX_KEY_BYTES + " bytes");
        }
        if (value.isEmpty() || value.size() > MessageType.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "A record value must be 1 to " + MessageType.MAX_PAYLOAD_BYTES + " bytes");
        }
    }

    /**
     * Builds a record from a message, checking that it is the topic's message type.
     *
     * @param topic the topic
     * @param key the id the topic is keyed by ({@link KairosTopic#keyType()})
     * @param message the message
     * @param traceParent the trace parent, or null
     * @return the record
     * @throws IllegalArgumentException if the message is not the topic's type or is too large
     */
    public static OutgoingRecord of(KairosTopic topic, UUID key, MessageLite message, @Nullable String traceParent) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(message, "message");
        if (!topic.messageType().type().isInstance(message)) {
            throw new IllegalArgumentException(topic.topicName() + " carries " + topic.messageType() + " messages");
        }
        return new OutgoingRecord(topic, ByteString.copyFrom(KeyType.key(key)), message.toByteString(), traceParent);
    }
}
