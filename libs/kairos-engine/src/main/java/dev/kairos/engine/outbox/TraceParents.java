package dev.kairos.engine.outbox;

import dev.kairos.messaging.trace.TraceHeaders;
import org.jspecify.annotations.Nullable;

/**
 * Validates W3C {@code traceparent} values before they are stored with an outbox row or put into a message.
 *
 * <p>A value is {@code version-traceId-parentId-flags}: 2, 32, 16 and 2 lower-case hex digits, 55 characters in all
 * (W3C Trace Context, section 3.2). Version {@code ff} and all-zero trace or parent ids are invalid. The length is
 * checked before the pattern runs, and the pattern has no nested quantifiers. The rules live in
 * {@link TraceHeaders#isValid(String)}, so a value stored here is always accepted when the relay puts it in a header.
 *
 * <p>Stateless and thread-safe.
 */
public final class TraceParents {

    /** Length of a version-00 {@code traceparent}. */
    public static final int LENGTH = TraceHeaders.TRACEPARENT_LENGTH;

    private TraceParents() {}

    /**
     * Tells whether a value is a valid {@code traceparent}.
     *
     * @param value the value to check
     * @return true if it is well formed and has a usable version, trace id and parent id
     */
    public static boolean isValid(String value) {
        return TraceHeaders.isValid(value);
    }

    /**
     * Returns the value if it is a valid {@code traceparent}, and {@code null} otherwise.
     *
     * <p>Tracing is best effort: a missing or malformed trace context drops the trace link but never fails the state
     * change it belongs to. Protobuf string fields use the empty string for "not set", which also maps to null.
     *
     * @param value the value, possibly null or empty
     * @return the valid value, or null
     */
    public static @Nullable String orNull(@Nullable String value) {
        return value == null || !isValid(value) ? null : value;
    }
}
