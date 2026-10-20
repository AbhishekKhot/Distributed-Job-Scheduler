package dev.kairos.messaging.serde;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import dev.kairos.proto.v1.Cancel;
import dev.kairos.proto.v1.Completed;
import dev.kairos.proto.v1.Dead;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.Result;
import java.util.List;
import java.util.Objects;

/**
 * One Kafka message contract of {@code kairos-proto} (LLD section 4.2) with its serializer and deserializer.
 *
 * <p>Immutable and thread-safe.
 *
 * @param <T> the message class
 */
public final class MessageType<T extends MessageLite> {

    /**
     * Largest record value: the {@code outbox} payload limit ({@code outbox_payload_ck}, 256 KiB). Every message is
     * far below it: the dispatch request body is capped by the outbox row, and results carry at most 24 KB of outputs
     * and a 1 KB snippet.
     */
    public static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    /** Dispatches on {@code kairos.run.high}, {@code .medium} and {@code .low}. */
    public static final MessageType<Dispatch> DISPATCH = new MessageType<>(Dispatch.class, Dispatch.parser());

    /** Call results on {@code kairos.run.result}. */
    public static final MessageType<Result> RESULT = new MessageType<>(Result.class, Result.parser());

    /** Cancel requests on {@code kairos.run.cancel}. */
    public static final MessageType<Cancel> CANCEL = new MessageType<>(Cancel.class, Cancel.parser());

    /** Completion events on {@code kairos.run.completed}. */
    public static final MessageType<Completed> COMPLETED = new MessageType<>(Completed.class, Completed.parser());

    /** Dead-letter events on {@code kairos.run.dead}. */
    public static final MessageType<Dead> DEAD = new MessageType<>(Dead.class, Dead.parser());

    private final Class<T> type;
    private final ProtobufSerializer<T> serializer;
    private final ProtobufDeserializer<T> deserializer;

    private MessageType(Class<T> type, Parser<T> parser) {
        this.type = Objects.requireNonNull(type, "type");
        this.serializer = new ProtobufSerializer<>(type);
        this.deserializer = new ProtobufDeserializer<>(parser);
    }

    /**
     * Returns every message type.
     *
     * @return the types, in contract order
     */
    public static List<MessageType<?>> all() {
        return List.of(DISPATCH, RESULT, CANCEL, COMPLETED, DEAD);
    }

    /**
     * Returns the message class.
     *
     * @return the class
     */
    public Class<T> type() {
        return type;
    }

    /**
     * Returns the shared serializer.
     *
     * @return the serializer
     */
    public ProtobufSerializer<T> serializer() {
        return serializer;
    }

    /**
     * Returns the shared deserializer.
     *
     * @return the deserializer
     */
    public ProtobufDeserializer<T> deserializer() {
        return deserializer;
    }

    @Override
    public String toString() {
        return type.getSimpleName();
    }
}
