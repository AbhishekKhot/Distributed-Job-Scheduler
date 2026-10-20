package dev.kairos.proto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProtoTimestampsTest {

    @Test
    void roundTripsWithNanosecondPrecision() {
        Instant instant = Instant.parse("2026-10-20T02:00:00.123456789Z");

        Timestamp timestamp = ProtoTimestamps.toTimestamp(instant);

        assertThat(timestamp.getSeconds()).isEqualTo(instant.getEpochSecond());
        assertThat(timestamp.getNanos()).isEqualTo(123_456_789);
        assertThat(ProtoTimestamps.toInstant(timestamp)).isEqualTo(instant);
    }

    @Test
    void acceptsTheRangeLimits() {
        Instant min = Instant.ofEpochSecond(ProtoTimestamps.MIN_SECONDS);
        Instant max = Instant.ofEpochSecond(ProtoTimestamps.MAX_SECONDS, 999_999_999);

        assertThat(ProtoTimestamps.toInstant(ProtoTimestamps.toTimestamp(min))).isEqualTo(min);
        assertThat(ProtoTimestamps.toInstant(ProtoTimestamps.toTimestamp(max))).isEqualTo(max);
    }

    @Test
    void rejectsInstantsOutsideTheRange() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProtoTimestamps.toTimestamp(Instant.ofEpochSecond(ProtoTimestamps.MAX_SECONDS + 1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProtoTimestamps.toTimestamp(Instant.ofEpochSecond(ProtoTimestamps.MIN_SECONDS - 1)));
    }

    @Test
    void rejectsCorruptTimestamps() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProtoTimestamps.toInstant(
                        Timestamp.newBuilder().setSeconds(Long.MAX_VALUE).build()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProtoTimestamps.toInstant(
                        Timestamp.newBuilder().setNanos(-1).build()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProtoTimestamps.toInstant(
                        Timestamp.newBuilder().setNanos(1_000_000_000).build()));
    }
}
