package dev.kairos.messaging.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import dev.kairos.messaging.MessagingFixtures;
import dev.kairos.proto.v1.Cancel;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.RequestSnapshot;
import java.util.Arrays;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.Test;

/** Serializers and deserializers of every message type, and the poison markers. */
class ProtobufSerdeTest {

    private static final String TOPIC = "kairos.run.high";

    @Test
    void everyMessageTypeRoundTrips() {
        for (MessageType<?> type : MessageType.all()) {
            assertRoundTrip(type);
        }
        assertThat(MessageType.all()).hasSize(5);
        assertThat(MessageType.DISPATCH.toString()).isEqualTo("Dispatch");
    }

    @Test
    void nullAndEmptyValuesArePoison() {
        assertThat(MessageType.DISPATCH.deserializer().deserialize(TOPIC, null))
                .isEqualTo(new Decoded.Poison<Dispatch>(PoisonReason.EMPTY, 0));
        assertThat(MessageType.DISPATCH.deserializer().deserialize(TOPIC, new byte[0]))
                .isEqualTo(new Decoded.Poison<Dispatch>(PoisonReason.EMPTY, 0));
        assertThat(MessageType.DISPATCH.serializer().serialize(TOPIC, null)).isNull();
    }

    @Test
    void malformedBytesArePoisonAndNeverThrow() {
        byte[] garbage = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        for (MessageType<?> type : MessageType.all()) {
            assertThat(type.deserializer().deserialize(TOPIC, garbage))
                    .isEqualTo(new Decoded.Poison<>(PoisonReason.MALFORMED, garbage.length));
        }
        // A field tag with wire type 7 does not exist.
        assertThat(MessageType.CANCEL.deserializer().deserialize(TOPIC, new byte[] {0x0f, 0x01}))
                .isInstanceOf(Decoded.Poison.class);
    }

    @Test
    void oversizedValuesArePoisonBeforeParsing() {
        byte[] large = new byte[MessageType.MAX_PAYLOAD_BYTES + 1];
        assertThat(MessageType.RESULT.deserializer().deserialize(TOPIC, large))
                .isEqualTo(new Decoded.Poison<>(PoisonReason.TOO_LARGE, large.length));
    }

    @Test
    void serializerRefusesOversizedAndForeignMessages() {
        byte[] body = new byte[MessageType.MAX_PAYLOAD_BYTES];
        Arrays.fill(body, (byte) 'a');
        Dispatch large = MessagingFixtures.dispatch().toBuilder()
                .setRequest(RequestSnapshot.newBuilder().setBody(ByteString.copyFrom(body)))
                .build();
        assertThatThrownBy(() -> MessageType.DISPATCH.serializer().serialize(TOPIC, large))
                .isInstanceOf(RecordTooLargeException.class)
                .hasMessageNotContaining("aaaa");

        @SuppressWarnings({"unchecked", "rawtypes"})
        ProtobufSerializer<MessageLite> raw = (ProtobufSerializer) MessageType.DISPATCH.serializer();
        assertThatThrownBy(() -> raw.serialize(TOPIC, MessagingFixtures.cancel()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void poisonMarkersValidateTheirFields() {
        assertThatThrownBy(() -> new Decoded.Poison<Cancel>(PoisonReason.EMPTY, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PoisonReason.TOO_LARGE.label()).isEqualTo("too_large");
    }

    private static <T extends MessageLite> void assertRoundTrip(MessageType<T> type) {
        T message = type.type().cast(MessagingFixtures.sample(type));
        byte[] bytes = type.serializer().serialize(TOPIC, message);
        assertThat(type.deserializer().deserialize(TOPIC, bytes)).isEqualTo(new Decoded.Valid<>(message));
    }
}
