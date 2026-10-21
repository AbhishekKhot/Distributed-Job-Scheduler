package dev.kairos.api.support.problem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.QuotaExceededException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class ProblemSupportTest {

    private final ProblemWriter writer = new ProblemWriter(
            JsonMapper.builder().build(),
            new TraceIds(new StaticListableBeanFactory().getBeanProvider(io.micrometer.tracing.Tracer.class)));

    @Test
    void retryAfterIsRoundedUpAndDefaulted() {
        assertThat(ProblemWriter.retryAfterSeconds(ApiException.rateLimited(Duration.ofMillis(1500))))
                .isEqualTo(2);
        assertThat(ProblemWriter.retryAfterSeconds(ApiException.rateLimited(Duration.ofNanos(1))))
                .isEqualTo(1);
        assertThat(ProblemWriter.retryAfterSeconds(new QuotaExceededException("The team has reached its limit.")))
                .isEqualTo(60);
        assertThat(ProblemWriter.retryAfterSeconds(ApiException.unavailable(Duration.ofSeconds(5))))
                .isEqualTo(5);
        assertThat(ProblemWriter.retryAfterSeconds(ApiException.unauthenticated()))
                .isEqualTo(-1);
    }

    @Test
    void writesTheProblemBodyAndHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(request, response, ApiException.rateLimited(Duration.ofSeconds(30)));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo(Problem.MEDIA_TYPE);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
        assertThat(response.getContentAsString())
                .isEqualTo("{\"type\":\"/errors/rate-limited\",\"title\":\"Too many requests\",\"status\":429,"
                        + "\"detail\":\"Too many requests. Retry after the time in Retry-After.\","
                        + "\"instance\":\"/v1/auth/login\",\"traceId\":\"unknown\"}");
    }

    @Test
    void problemsCarryTheCodeOfTheException() {
        Problem problem = writer.problem(
                new MockHttpServletRequest("GET", "/v1/jobs"),
                new ConflictException(ErrorCode.JOB_NAME_TAKEN, "A job with this name already exists in the team."));

        assertThat(problem.type()).isEqualTo("/errors/job-name-taken");
        assertThat(problem.status()).isEqualTo(409);
        assertThat(problem.errors()).isNull();
        assertThat(new Problem("t", "t", 400, "d", "/", "x", List.of()).errors())
                .isNull();
    }

    @Test
    void containerErrorsMapToLldCodes() {
        assertThat(ProblemErrorController.codeFor(401)).isEqualTo(ErrorCode.UNAUTHENTICATED);
        assertThat(ProblemErrorController.codeFor(404)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ProblemErrorController.codeFor(405)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ProblemErrorController.codeFor(413)).isEqualTo(ErrorCode.VALIDATION);
        assertThat(ProblemErrorController.codeFor(429)).isEqualTo(ErrorCode.RATE_LIMITED);
        assertThat(ProblemErrorController.codeFor(503)).isEqualTo(ErrorCode.UNAVAILABLE);
        assertThat(ProblemErrorController.codeFor(500)).isEqualTo(ErrorCode.INTERNAL);
    }

    @Test
    void containerErrorDispatchesBecomeProblems() {
        ProblemErrorController controller = new ProblemErrorController(writer);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 503);
        request.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, "/v1/jobs");

        var response = controller.error(request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(response.getBody().instance()).isEqualTo("/v1/jobs");
        assertThat(controller
                        .error(new MockHttpServletRequest("GET", "/error"))
                        .getStatusCode()
                        .value())
                .isEqualTo(500);
    }

    @Test
    void constraintsMapToFieldCodes() {
        assertThat(ProblemDetailsAdvice.codeFor("NotBlank")).isEqualTo("required");
        assertThat(ProblemDetailsAdvice.codeFor("Size")).isEqualTo("length");
        assertThat(ProblemDetailsAdvice.codeFor("Pattern")).isEqualTo("pattern");
        assertThat(ProblemDetailsAdvice.codeFor("Max")).isEqualTo("range");
        assertThat(ProblemDetailsAdvice.codeFor("typeMismatch")).isEqualTo("format");
        assertThat(ProblemDetailsAdvice.codeFor("Custom")).isEqualTo("invalid");
        assertThat(ProblemDetailsAdvice.codeFor(null)).isEqualTo("invalid");
    }
}
