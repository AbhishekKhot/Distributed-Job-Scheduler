package dev.kairos.state.settle;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import dev.kairos.proto.v1.Result;
import dev.kairos.state.settle.application.ResultMetadata;
import dev.kairos.state.settle.infrastructure.SettlementProperties;
import java.time.Duration;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ResultMetadataTest {
    @Test
    void acceptsValidMetadata() {
        assertThatCode(() -> ResultMetadata.requireValid(ResultConsumerTest.valid()))
                .doesNotThrowAnyException();
    }

    static Stream<UnaryOperator<Result.Builder>> invalid() {
        return Stream.of(
                b -> b.setRunId(ByteString.EMPTY),
                b -> b.setShard(13),
                b -> b.setFence(0),
                b -> b.setAttempt(0),
                b -> b.setAttempt(32768),
                b -> b.setInfraAttempt(4),
                b -> b.setOutcomeValue(99),
                b -> b.setErrorClassValue(15),
                b -> b.setHttpStatus(600),
                b -> b.setLatencyMs(-1),
                b -> b.setRetryAfterMs(-1),
                b -> b.setRetryAfterMs(604800001),
                b -> b.clearStartedAt(),
                b -> b.clearFinishedAt(),
                b -> b.setExecutorId(" "),
                b -> b.setExecutorId("e".repeat(129)),
                b -> b.setError("e".repeat(513)),
                b -> b.setResponseSnippet("界".repeat(342)),
                b -> b.setFinishedAt(b.getStartedAt().toBuilder().setSeconds(1)),
                b -> b.setErrorClassValue(1),
                b -> b.setOutcomeValue(2),
                b -> b.setOutcomeValue(6).setSentMarker(true),
                b -> b.setSealedOutputs(ByteString.copyFrom(new byte[24577])));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void rejectsBeforeDatabaseAccess(UnaryOperator<Result.Builder> change) {
        assertThatThrownBy(() -> ResultMetadata.requireValid(
                        change.apply(ResultConsumerTest.valid().toBuilder()).build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsConsumerSettings() {
        assertThatCode(() -> new SettlementProperties("state", 1000, Duration.ofMillis(250), Duration.ofMillis(500)))
                .doesNotThrowAnyException();
        for (int size : new int[] {0, 1001})
            assertThatThrownBy(
                            () -> new SettlementProperties("state", size, Duration.ofMillis(1), Duration.ofMillis(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new SettlementProperties("bad group", 1000, Duration.ofMillis(1), Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementProperties("state", 1000, Duration.ZERO, Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementProperties("state", 1000, Duration.ofSeconds(6), Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementProperties("state", 1000, Duration.ofMillis(1), Duration.ofSeconds(11)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
