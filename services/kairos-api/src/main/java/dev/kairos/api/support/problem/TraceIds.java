package dev.kairos.api.support.problem;

import dev.kairos.api.support.web.RequestIdFilter;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Finds the trace id that a problem body reports, so a client can quote it and an operator can find the request's
 * logs and spans.
 *
 * <p>The current span's trace id is used when tracing is active. Before the tracing filter has run (very early
 * failures) the trace id in the logging context is used, and failing that the request id, which has the same shape.
 * Thread-safe.
 */
public final class TraceIds {

    private static final String MDC_TRACE_ID = "traceId";

    private final ObjectProvider<Tracer> tracer;

    /**
     * Creates the lookup.
     *
     * @param tracer the service's tracer, if tracing is configured
     */
    public TraceIds(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /**
     * Returns the trace id of the current request.
     *
     * @param request the request
     * @return a lower-case hex trace id, or the request id when no trace is active
     */
    public String current(HttpServletRequest request) {
        Tracer current = tracer.getIfAvailable();
        if (current != null) {
            Span span = current.currentSpan();
            if (span != null && isHexId(span.context().traceId())) {
                return span.context().traceId();
            }
        }
        String fromLogs = MDC.get(MDC_TRACE_ID);
        if (isHexId(fromLogs)) {
            return fromLogs;
        }
        return RequestIdFilter.requestId(request);
    }

    private static boolean isHexId(@Nullable String value) {
        if (value == null || (value.length() != 16 && value.length() != 32)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return !value.chars().allMatch(c -> c == '0');
    }
}
