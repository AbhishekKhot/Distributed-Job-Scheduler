package dev.kairos.api.support.problem;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.ValidationFailedException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers the servlet container's error dispatch ({@code /error}) with a problem body, replacing Spring Boot's
 * default error page. It handles failures that never reached a controller, such as a request the container rejected;
 * everything a controller raises is mapped by {@link ProblemDetailsAdvice} instead.
 *
 * <p>The original status is mapped onto the LLD codes: 400 range to {@code validation}, 401 to
 * {@code unauthenticated}, 404 and 405 to {@code not-found}, 429 to {@code rate-limited}, 503 to
 * {@code unavailable} and everything else to {@code internal}. Nothing from the failed request is echoed besides its
 * path.
 */
@Hidden
@RestController
public class ProblemErrorController implements ErrorController {

    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType(Problem.MEDIA_TYPE);

    private final ProblemWriter problems;

    /**
     * Creates the controller.
     *
     * @param problems builds problem bodies
     */
    public ProblemErrorController(ProblemWriter problems) {
        this.problems = problems;
    }

    /**
     * Writes the problem for the error dispatch.
     *
     * @param request the error dispatch request
     * @return the problem response
     */
    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<Problem> error(HttpServletRequest request) {
        int status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code ? code : 500;
        String path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String uri
                ? uri
                : request.getRequestURI();
        ErrorCode code = codeFor(status);
        String detail = switch (code) {
            case UNAUTHENTICATED -> ApiException.UNAUTHENTICATED_DETAIL;
            case NOT_FOUND -> ProblemDetailsAdvice.NOT_FOUND_DETAIL;
            case RATE_LIMITED -> ApiException.RATE_LIMITED_DETAIL;
            case UNAVAILABLE -> ApiException.UNAVAILABLE_DETAIL;
            case VALIDATION -> ValidationFailedException.DEFAULT_DETAIL;
            default -> ApiException.INTERNAL_DETAIL;
        };
        Problem built = problems.problem(request, code, detail, List.of());
        Problem problem =
                new Problem(built.type(), built.title(), built.status(), built.detail(), path, built.traceId(), null);
        ResponseEntity.BodyBuilder response =
                ResponseEntity.status(problem.status()).contentType(PROBLEM_JSON);
        if (code == ErrorCode.RATE_LIMITED) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(ProblemWriter.DEFAULT_RATE_LIMIT_RETRY.toSeconds()));
        } else if (code == ErrorCode.UNAVAILABLE) {
            response.header(
                    HttpHeaders.RETRY_AFTER, Long.toString(ProblemWriter.DEFAULT_UNAVAILABLE_RETRY.toSeconds()));
        }
        return response.body(problem);
    }

    static ErrorCode codeFor(int status) {
        return switch (status) {
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 404, 405 -> ErrorCode.NOT_FOUND;
            case 429 -> ErrorCode.RATE_LIMITED;
            case 503 -> ErrorCode.UNAVAILABLE;
            default -> status >= 400 && status < 500 ? ErrorCode.VALIDATION : ErrorCode.INTERNAL;
        };
    }
}
