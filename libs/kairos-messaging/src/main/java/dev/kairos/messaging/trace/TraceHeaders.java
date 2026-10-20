package dev.kairos.messaging.trace;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.propagation.Propagator;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.jspecify.annotations.Nullable;

/**
 * Carries the W3C trace context in Kafka record headers (IMPLEMENTATION_CHECKLIST section 4.7).
 *
 * <p>Two ways in and out:
 *
 * <ul>
 *   <li>the stored form: {@link #setTraceParent} and {@link #traceParent} write and read the raw {@code traceparent}
 *       header, for the outbox relay, which publishes the value stored with the outbox row;
 *   <li>the live form: {@link #inject} and {@link #extract} go through the service's Micrometer Tracing
 *       {@link Propagator}, so the current span (an executor publishing a result) is propagated and a consumer
 *       continues the producer's trace.
 * </ul>
 *
 * <p>Tracing is best effort: a missing or malformed header never fails a record; it only drops the trace link. A value
 * is accepted with the same rules as the outbox's {@code trace_parent} column ({@code TraceParents} in
 * {@code kairos-engine}): {@code version-traceId-parentId-flags}, 2, 32, 16 and 2 lower-case hex digits, version not
 * {@code ff}, trace and parent ids not all zero. The length is checked before the anchored pattern runs. Header values
 * are capped at {@value #MAX_HEADER_BYTES} bytes before they are decoded.
 *
 * <p>Thread-safe.
 */
public final class TraceHeaders {

    /** The W3C trace context header. */
    public static final String TRACEPARENT = "traceparent";

    /** The W3C trace state header. */
    public static final String TRACESTATE = "tracestate";

    /** Length of a version-00 {@code traceparent}. */
    public static final int TRACEPARENT_LENGTH = 55;

    /** Largest header value read through the propagator; W3C caps {@code tracestate} at 512 characters. */
    public static final int MAX_HEADER_BYTES = 512;

    private static final Pattern FORMAT = Pattern.compile("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final String INVALID_VERSION = "ff";
    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_PARENT_ID = "0".repeat(16);

    private static final Propagator.Setter<Headers> SETTER = (headers, name, value) -> {
        if (headers != null) {
            headers.remove(name);
            headers.add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    };

    private static final Propagator.Getter<Headers> GETTER = TraceHeaders::lastValue;

    private final Propagator propagator;

    /**
     * Creates the helper for the service's propagator.
     *
     * @param propagator the Micrometer Tracing propagator, {@link Propagator#NOOP} when tracing is off
     */
    public TraceHeaders(Propagator propagator) {
        this.propagator = Objects.requireNonNull(propagator, "propagator");
    }

    /**
     * Writes a trace context into the headers, replacing any earlier value.
     *
     * @param context the context of the current span
     * @param headers the record's headers
     */
    public void inject(TraceContext context, Headers headers) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(headers, "headers");
        propagator.inject(context, headers, SETTER);
    }

    /**
     * Starts a span builder that continues the trace of the headers; without a valid context it starts a new trace.
     *
     * @param headers the record's headers
     * @return the span builder
     */
    public Span.Builder extract(Headers headers) {
        Objects.requireNonNull(headers, "headers");
        return propagator.extract(headers, GETTER);
    }

    /**
     * Writes a stored trace parent into the {@link #TRACEPARENT} header, replacing any earlier one; a null or malformed
     * value only removes it.
     *
     * @param headers the record's headers
     * @param traceParent the value, possibly null
     */
    public static void setTraceParent(Headers headers, @Nullable String traceParent) {
        Objects.requireNonNull(headers, "headers");
        headers.remove(TRACEPARENT);
        if (traceParent != null && isValid(traceParent)) {
            headers.add(TRACEPARENT, traceParent.getBytes(StandardCharsets.US_ASCII));
        }
    }

    /**
     * Reads the {@link #TRACEPARENT} header.
     *
     * @param headers the record's headers
     * @return the last valid value, or empty when it is missing or malformed
     */
    public static Optional<String> traceParent(Headers headers) {
        Objects.requireNonNull(headers, "headers");
        String value = lastValue(headers, TRACEPARENT);
        return value != null && isValid(value) ? Optional.of(value) : Optional.empty();
    }

    /**
     * Tells whether a value is a valid {@code traceparent}.
     *
     * @param value the value
     * @return true if it is well formed with a usable version, trace id and parent id
     */
    public static boolean isValid(String value) {
        if (value.length() != TRACEPARENT_LENGTH || !FORMAT.matcher(value).matches()) {
            return false;
        }
        return !value.startsWith(INVALID_VERSION)
                && !value.substring(3, 35).equals(ZERO_TRACE_ID)
                && !value.substring(36, 52).equals(ZERO_PARENT_ID);
    }

    private static @Nullable String lastValue(Headers headers, String name) {
        Header header = headers.lastHeader(name);
        if (header == null) {
            return null;
        }
        byte[] value = header.value();
        if (value == null || value.length == 0 || value.length > MAX_HEADER_BYTES) {
            return null;
        }
        return new String(value, StandardCharsets.UTF_8);
    }
}
