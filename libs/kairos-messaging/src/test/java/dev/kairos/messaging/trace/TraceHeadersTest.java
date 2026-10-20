package dev.kairos.messaging.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The stored and live forms of the trace context header. The validity rules match the outbox's {@code TraceParents}. */
class TraceHeadersTest {

    private static final String VALID = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String OTHER = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @ParameterizedTest
    @ValueSource(strings = {VALID, "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00"})
    void acceptsWellFormedValues(String value) {
        assertThat(TraceHeaders.isValid(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
                "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra",
                "00-4bf92f3577b34da6a3ce929d0e0e4736_00f067aa0ba902b7-01",
                "not a trace parent",
                ""
            })
    void rejectsMalformedValues(String value) {
        assertThat(TraceHeaders.isValid(value)).isFalse();
        Headers headers = new RecordHeaders();
        TraceHeaders.setTraceParent(headers, value);
        assertThat(headers.lastHeader(TraceHeaders.TRACEPARENT)).isNull();
        headers.add(TraceHeaders.TRACEPARENT, value.getBytes(StandardCharsets.UTF_8));
        assertThat(TraceHeaders.traceParent(headers)).isEmpty();
    }

    @Test
    void storedValuesReplaceEarlierOnesAndReadBack() {
        Headers headers = new RecordHeaders();
        TraceHeaders.setTraceParent(headers, OTHER);
        TraceHeaders.setTraceParent(headers, VALID);

        assertThat(headers.headers(TraceHeaders.TRACEPARENT)).hasSize(1);
        assertThat(TraceHeaders.traceParent(headers)).contains(VALID);

        TraceHeaders.setTraceParent(headers, null);
        assertThat(TraceHeaders.traceParent(headers)).isEmpty();
    }

    @Test
    void oversizedOrEmptyHeadersAreIgnored() {
        Headers headers = new RecordHeaders();
        headers.add(TraceHeaders.TRACEPARENT, new byte[TraceHeaders.MAX_HEADER_BYTES + 1]);
        assertThat(TraceHeaders.traceParent(headers)).isEmpty();
        headers.add(TraceHeaders.TRACEPARENT, new byte[0]);
        assertThat(TraceHeaders.traceParent(headers)).isEmpty();
        headers.add(TraceHeaders.TRACEPARENT, (byte[]) null);
        assertThat(TraceHeaders.traceParent(headers)).isEmpty();
    }

    @Test
    void injectsAndExtractsThroughThePropagator() {
        RecordingPropagator propagator = new RecordingPropagator();
        TraceHeaders traceHeaders = new TraceHeaders(propagator);
        Headers headers = new RecordHeaders();
        headers.add(TraceHeaders.TRACEPARENT, OTHER.getBytes(StandardCharsets.US_ASCII));

        traceHeaders.inject(TraceContext.NOOP, headers);
        assertThat(headers.headers(TraceHeaders.TRACEPARENT)).hasSize(1);
        assertThat(TraceHeaders.traceParent(headers)).contains(VALID);

        Span.Builder builder = traceHeaders.extract(headers);
        assertThat(builder).isNotNull();
        assertThat(propagator.extracted.get()).isEqualTo(VALID);
    }

    @Test
    void theNoopPropagatorLeavesHeadersAlone() {
        TraceHeaders traceHeaders = new TraceHeaders(Propagator.NOOP);
        Headers headers = new RecordHeaders();

        traceHeaders.inject(TraceContext.NOOP, headers);

        assertThat(headers.toArray()).isEmpty();
        assertThat(traceHeaders.extract(headers)).isNotNull();
    }

    /** Writes a fixed {@code traceparent} and records what it reads, as a W3C propagator would. */
    private static final class RecordingPropagator implements Propagator {

        private final AtomicReference<@Nullable String> extracted = new AtomicReference<>();

        @Override
        public List<String> fields() {
            return List.of(TraceHeaders.TRACEPARENT);
        }

        @Override
        public <C> void inject(TraceContext context, @Nullable C carrier, Setter<C> setter) {
            setter.set(carrier, TraceHeaders.TRACEPARENT, VALID);
        }

        @Override
        public <C> Span.Builder extract(C carrier, Getter<C> getter) {
            extracted.set(getter.get(carrier, TraceHeaders.TRACEPARENT));
            return Span.Builder.NOOP;
        }
    }
}
