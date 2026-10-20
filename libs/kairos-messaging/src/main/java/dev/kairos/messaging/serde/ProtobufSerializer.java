package dev.kairos.messaging.serde;

import com.google.protobuf.MessageLite;
import java.util.Objects;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.serialization.Serializer;
import org.jspecify.annotations.Nullable;

/**
 * Serializes one Kairos Protobuf message type for a Kafka producer.
 *
 * <p>A message larger than {@link MessageType#MAX_PAYLOAD_BYTES} is refused with {@link RecordTooLargeException}
 * (LLD section 2.3, {@code outbox_payload_ck}), so an oversized message fails at the producer instead of being
 * published and poisoning its consumers.
 *
 * <p>Stateless and thread-safe.
 *
 * @param <T> the message type
 */
public final class ProtobufSerializer<T extends MessageLite> implements Serializer<T> {

    private final Class<T> type;

    /**
     * Creates a serializer.
     *
     * @param type the message class, checked on every call
     */
    public ProtobufSerializer(Class<T> type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /**
     * Serializes a message.
     *
     * @param topic the topic the record goes to
     * @param message the message; null stays null
     * @return the encoded message, or null for a null message
     * @throws IllegalArgumentException if the message is not of this serializer's type
     * @throws RecordTooLargeException if the encoding is larger than {@link MessageType#MAX_PAYLOAD_BYTES}
     */
    @Override
    public byte @Nullable [] serialize(String topic, @Nullable T message) {
        if (message == null) {
            return null;
        }
        if (!type.isInstance(message)) {
            throw new IllegalArgumentException("Expected a " + type.getSimpleName() + " message");
        }
        int size = message.getSerializedSize();
        if (size > MessageType.MAX_PAYLOAD_BYTES) {
            throw new RecordTooLargeException("A " + type.getSimpleName() + " message is " + size
                    + " bytes, more than the " + MessageType.MAX_PAYLOAD_BYTES + "-byte limit");
        }
        return message.toByteArray();
    }
}
