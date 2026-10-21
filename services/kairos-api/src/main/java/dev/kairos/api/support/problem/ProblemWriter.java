package dev.kairos.api.support.problem;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.KairosException;
import dev.kairos.domain.error.ValidationFailedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds problem bodies and writes them from places that run outside Spring MVC, such as the security and rate-limit
 * filters.
 *
 * <p>A 429 or 503 always carries {@code Retry-After} (LLD section 3.1): the exception's own wait when it has one,
 * otherwise {@link #DEFAULT_RATE_LIMIT_RETRY} or {@link #DEFAULT_UNAVAILABLE_RETRY}. Thread-safe.
 */
public final class ProblemWriter {

    /** {@code Retry-After} of a 429 that has no wait of its own, such as a team quota. */
    public static final Duration DEFAULT_RATE_LIMIT_RETRY = Duration.ofSeconds(60);

    /** {@code Retry-After} of a 503 that has no wait of its own. */
    public static final Duration DEFAULT_UNAVAILABLE_RETRY = Duration.ofSeconds(5);

    private final JsonMapper json;
    private final TraceIds traceIds;

    /**
     * Creates the writer.
     *
     * @param json the service's JSON mapper
     * @param traceIds finds the trace id of a request
     */
    public ProblemWriter(JsonMapper json, TraceIds traceIds) {
        this.json = json;
        this.traceIds = traceIds;
    }

    /**
     * Builds the problem of a domain or API exception.
     *
     * @param request the request
     * @param failure the exception
     * @return the problem
     */
    public Problem problem(HttpServletRequest request, KairosException failure) {
        List<FieldError> errors =
                failure instanceof ValidationFailedException validation ? validation.fieldErrors() : List.of();
        return problem(request, failure.errorCode(), failure.detail(), errors);
    }

    /**
     * Builds a problem.
     *
     * @param request the request
     * @param code the error code
     * @param detail the fixed detail sentence
     * @param errors the invalid fields, possibly empty
     * @return the problem
     */
    public Problem problem(HttpServletRequest request, ErrorCode code, String detail, List<FieldError> errors) {
        return Problem.of(code, detail, request.getRequestURI(), traceIds.current(request), errors);
    }

    /**
     * Returns the {@code Retry-After} value of an exception, in whole seconds, or -1 when the status needs none.
     *
     * @param failure the exception
     * @return the seconds to wait (at least 1), or -1
     */
    public static long retryAfterSeconds(KairosException failure) {
        int status = failure.errorCode().status();
        if (status != 429 && status != 503) {
            return -1;
        }
        Duration fallback = status == 429 ? DEFAULT_RATE_LIMIT_RETRY : DEFAULT_UNAVAILABLE_RETRY;
        Duration wait = failure instanceof ApiException api ? api.retryAfter().orElse(fallback) : fallback;
        // Round up so the client never retries before the limit has refilled.
        long seconds = wait.toSeconds() + (wait.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }

    /**
     * Writes the problem of an exception as the response.
     *
     * @param request the request
     * @param response the response, not yet committed
     * @param failure the exception
     * @throws IOException if the body cannot be written
     */
    public void write(HttpServletRequest request, HttpServletResponse response, KairosException failure)
            throws IOException {
        long retryAfter = retryAfterSeconds(failure);
        if (retryAfter > 0) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        }
        write(response, problem(request, failure));
    }

    /**
     * Writes a problem as the response.
     *
     * @param response the response, not yet committed
     * @param problem the problem
     * @throws IOException if the body cannot be written
     */
    public void write(HttpServletResponse response, Problem problem) throws IOException {
        response.setStatus(problem.status());
        response.setContentType(Problem.MEDIA_TYPE);
        response.getOutputStream().write(json.writeValueAsBytes(problem));
        response.flushBuffer();
    }
}
