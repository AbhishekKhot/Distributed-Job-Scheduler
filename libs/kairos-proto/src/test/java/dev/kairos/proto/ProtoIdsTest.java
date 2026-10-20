package dev.kairos.proto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.google.protobuf.ByteString;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProtoIdsTest {

    @Test
    void encodesBigEndianLikePostgres() {
        UUID id = UUID.fromString("0192e8c4-2a1b-7f3c-8d4e-5f6a7b8c9d2a");

        ByteString bytes = ProtoIds.toBytes(id);

        assertThat(HexFormat.of().formatHex(bytes.toByteArray())).isEqualTo("0192e8c42a1b7f3c8d4e5f6a7b8c9d2a");
    }

    @Test
    void roundTrips() {
        UUID id = UUID.randomUUID();

        assertThat(ProtoIds.toUuid(ProtoIds.toBytes(id))).isEqualTo(id);
    }

    @Test
    void rejectsUnsetId() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProtoIds.toUuid(ByteString.EMPTY))
                .withMessageContaining("16 bytes");
    }

    @Test
    void rejectsWrongLength() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProtoIds.toUuid(ByteString.copyFrom(new byte[17])))
                .withMessageContaining("was 17 bytes");
    }
}
