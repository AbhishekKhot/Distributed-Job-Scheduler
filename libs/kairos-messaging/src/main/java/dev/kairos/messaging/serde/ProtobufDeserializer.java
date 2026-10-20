package dev.kairos.messaging.serde;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import java.util.Objects;
import org.apache.kafka.common.serialization.Deserializer;
import org.jspecify.annotations.Nullable;

/**
 * Decodes one Kairos Protobuf message type for a Kafka consumer, without ever throwing.
 *
 * <p>A null or empty value, a value larger than {@link MessageType#MAX_PAYLOAD_BYTES} (checked before parsing) and
 * bytes that are not a valid encoding all decode to {@link Decoded.Poison}. Unknown fields are kept, so a consumer reads
 * messages from a newer producer (LLD section 4.2, compatibility). Semantic checks (16-byte ids, ranges) are the
 * consumer's job.
 *
 * <p>Stateless and thread-safe.
 *
 * @param <T> the message type
 */
public final class ProtobufDeserializer<T extends MessageLite> implements Deserializer<Decoded<T>> {

    private final Parser<T> parser;

    /**
     * Creates a deserializer.
     *
     * @param parser the message type's parser
     */
    public ProtobufDeserializer(Parser<T> parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    /**
     * Decodes a record value.
     *
     * @param topic the topic the record came from
     * @param data the value, possibly null
     * @return the message, or a poison marker
     */
    @Override
    public Decoded<T> deserialize(String topic, byte @Nullable [] data) {
        if (data == null || data.length == 0) {
            return new Decoded.Poison<>(PoisonReason.EMPTY, 0);
        }
        if (data.length > MessageType.MAX_PAYLOAD_BYTES) {
            return new Decoded.Poison<>(PoisonReason.TOO_LARGE, data.length);
        }
        try {
            return new Decoded.Valid<>(parser.parseFrom(data));
        } catch (InvalidProtocolBufferException | RuntimeException e) {
            // A malformed value must never escape as an exception: it would stop the poll loop on this record.
            return new Decoded.Poison<>(PoisonReason.MALFORMED, data.length);
        }
    }
}
