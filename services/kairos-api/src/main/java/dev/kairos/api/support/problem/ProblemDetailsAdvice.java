package dev.kairos.api.support.problem;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.KairosException;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.persistence.error.RetryableDatabaseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindingResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Turns every exception that reaches Spring MVC into an RFC 9457 problem with an LLD error code (section 3.1).
 *
 * <ul>
 *   <li>{@link KairosException}: its own code, detail and field errors; 429 and 503 get {@code Retry-After}.
 *   <li>Bean Validation failures, malformed or unreadable JSON, unknown fields, wrong parameter types and missing
 *       headers or parameters: 400 {@code validation}, with the field path and a stable code. Submitted values are
 *       never echoed.
 *   <li>Unsupported or unacceptable media types: 400 {@code validation}.
 *   <li>Unknown paths and unsupported methods: 404 {@code not-found}, so the API surface is the same with or without
 *       a matching route.
 *   <li>A database that cannot be reached or timed out: 503 {@code unavailable}.
 *   <li>Anything else: a generic 500 {@code internal} with the trace id; the exception is logged server-side.
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProblemDetailsAdvice {

    /** Detail of a generic 404. */
    public static final String NOT_FOUND_DETAIL = "The resource does not exist.";

    /** Detail of a body that is not valid JSON or does not match the expected shape. */
    public static final String UNREADABLE_BODY_DETAIL = "The request body is not valid JSON for this operation.";

    /** Detail of a request whose content type is not JSON. */
    public static final String UNSUPPORTED_MEDIA_TYPE_DETAIL = "The request body must be sent as application/json.";

    /** Detail of a request that accepts no JSON response. */
    public static final String NOT_ACCEPTABLE_DETAIL = "Responses are only available as application/json.";

    /** Field error code of a field that the request type does not have. */
    public static final String UNKNOWN_FIELD = "unknown-field";

    /** Field error code of a constraint without a more specific code. */
    public static final String INVALID = "invalid";

    private static final int MAX_FIELD_PATH = 128;

    private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    private static final MediaType PROBLEM_JSON = MediaType.parseMediaType(Problem.MEDIA_TYPE);

    private final ProblemWriter problems;

    /**
     * Creates the advice.
     *
     * @param problems builds problem bodies
     */
    public ProblemDetailsAdvice(ProblemWriter problems) {
        this.problems = problems;
    }

    /**
     * Maps a domain or API exception to its own code.
     *
     * @param failure the exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler(KairosException.class)
    public ResponseEntity<Problem> kairos(KairosException failure, HttpServletRequest request) {
        Problem problem = problems.problem(request, failure);
        if (problem.status() >= 500) {
            LOG.warn("Request failed with {} (traceId {})", failure.errorCode().slug(), problem.traceId());
        } else {
            LOG.debug(
                    "Request rejected with {} (traceId {})", failure.errorCode().slug(), problem.traceId());
        }
        ResponseEntity.BodyBuilder response =
                ResponseEntity.status(problem.status()).contentType(PROBLEM_JSON);
        long retryAfter = ProblemWriter.retryAfterSeconds(failure);
        if (retryAfter > 0) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        }
        return response.body(problem);
    }

    /**
     * Maps Bean Validation failures of a request body.
     *
     * @param failure the exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Problem> invalidBody(MethodArgumentNotValidException failure, HttpServletRequest request) {
        return validation(request, fieldErrors(failure.getBindingResult()));
    }

    /**
     * Maps Bean Validation failures of parameters and headers.
     *
     * @param failure the exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Problem> invalidParameters(
            HandlerMethodValidationException failure, HttpServletRequest request) {
        List<FieldError> errors = new ArrayList<>();
        failure.getParameterValidationResults().forEach(result -> {
            String name = parameterName(result.getMethodParameter());
            result.getResolvableErrors().forEach(error -> errors.add(new FieldError(name, code(error))));
        });
        return validation(request, errors);
    }

    /**
     * Maps constraint violations raised by validated components.
     *
     * @param failure the exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Problem> constraintViolations(
            ConstraintViolationException failure, HttpServletRequest request) {
        List<FieldError> errors = new ArrayList<>();
        failure.getConstraintViolations().forEach(violation -> {
            String constraint = violation
                    .getConstraintDescriptor()
                    .getAnnotation()
                    .annotationType()
                    .getSimpleName();
            errors.add(new FieldError(leafName(violation.getPropertyPath()), codeFor(constraint)));
        });
        return validation(request, errors);
    }

    /**
     * Maps a body that is not JSON, has the wrong shape or carries unknown fields.
     *
     * @param failure the exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Problem> unreadableBody(HttpMessageNotReadableException failure, HttpServletRequest request) {
        Throwable cause = failure.getMostSpecificCause();
        if (cause instanceof UnrecognizedPropertyException unknown) {
            return validation(
                    request, List.of(new FieldError(jsonPath(unknown, unknown.getPropertyName()), UNKNOWN_FIELD)));
        }
        if (cause instanceof DatabindException mismatch && !mismatch.getPath().isEmpty()) {
            return respond(
                    request,
                    ErrorCode.VALIDATION,
                    UNREADABLE_BODY_DETAIL,
                    List.of(new FieldError(jsonPath(mismatch, null), FieldError.FORMAT)));
        }
        return respond(
                request,
                ErrorCode.VALIDATION,
                UNREADABLE_BODY_DETAIL,
                List.of(new FieldError("body", FieldError.FORMAT)));
    }

    /**
     * Maps a missing header.
     *
     * @param failure the exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Problem> missingHeader(MissingRequestHeaderException failure, HttpServletRequest request) {
        return validation(request, List.of(new FieldError(failure.getHeaderName(), FieldError.REQUIRED)));
    }

    /**
     * Maps a missing query parameter.
     *
     * @param failure the exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Problem> missingParameter(
            MissingServletRequestParameterException failure, HttpServletRequest request) {
        return validation(request, List.of(new FieldError(failure.getParameterName(), FieldError.REQUIRED)));
    }

    /**
     * Maps a path, query or header value that cannot be converted to its type.
     *
     * @param failure the exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Problem> typeMismatch(
            MethodArgumentTypeMismatchException failure, HttpServletRequest request) {
        return validation(request, List.of(new FieldError(parameterName(failure.getParameter()), FieldError.FORMAT)));
    }

    /**
     * Maps an unsupported request content type.
     *
     * @param failure the exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Problem> unsupportedMediaType(
            HttpMediaTypeNotSupportedException failure, HttpServletRequest request) {
        return respond(
                request,
                ErrorCode.VALIDATION,
                UNSUPPORTED_MEDIA_TYPE_DETAIL,
                List.of(new FieldError(HttpHeaders.CONTENT_TYPE, FieldError.FORMAT)));
    }

    /**
     * Maps an {@code Accept} header that allows no JSON response.
     *
     * @param failure the exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Problem> notAcceptable(
            HttpMediaTypeNotAcceptableException failure, HttpServletRequest request) {
        return respond(
                request,
                ErrorCode.VALIDATION,
                NOT_ACCEPTABLE_DETAIL,
                List.of(new FieldError(HttpHeaders.ACCEPT, FieldError.FORMAT)));
    }

    /**
     * Maps unknown paths and methods to 404.
     *
     * @param failure the exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler({
        NoResourceFoundException.class,
        NoHandlerFoundException.class,
        HttpRequestMethodNotSupportedException.class
    })
    public ResponseEntity<Problem> notFound(Exception failure, HttpServletRequest request) {
        return respond(request, ErrorCode.NOT_FOUND, NOT_FOUND_DETAIL, List.of());
    }

    /**
     * Maps a database that cannot be reached, timed out or must be retried to 503.
     *
     * @param failure the exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler({
        RetryableDatabaseException.class,
        CannotGetJdbcConnectionException.class,
        DataAccessResourceFailureException.class,
        TransientDataAccessException.class,
        RecoverableDataAccessException.class,
        QueryTimeoutException.class
    })
    public ResponseEntity<Problem> databaseUnavailable(Exception failure, HttpServletRequest request) {
        ApiException unavailable = ApiException.unavailable(ProblemWriter.DEFAULT_UNAVAILABLE_RETRY);
        Problem problem = problems.problem(request, unavailable);
        LOG.warn("Database unavailable: {} (traceId {})", failure.getClass().getSimpleName(), problem.traceId());
        return ResponseEntity.status(problem.status())
                .contentType(PROBLEM_JSON)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ProblemWriter.retryAfterSeconds(unavailable)))
                .body(problem);
    }

    /**
     * Lets Spring Security handle its own exceptions (the API never raises them from controllers).
     *
     * @param failure the exception
     * @throws Exception always, the same exception
     */
    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    public void security(Exception failure) throws Exception {
        throw failure;
    }

    /**
     * Maps everything else: framework exceptions that carry a 4xx status become 400 or 404, and the rest a generic
     * 500 whose details are logged, never returned.
     *
     * @param failure the exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> unexpected(Exception failure, HttpServletRequest request) {
        if (failure instanceof ErrorResponse framework) {
            int status = framework.getStatusCode().value();
            if (status == 404 || status == 405) {
                return respond(request, ErrorCode.NOT_FOUND, NOT_FOUND_DETAIL, List.of());
            }
            if (status >= 400 && status < 500) {
                return respond(request, ErrorCode.VALIDATION, UNREADABLE_BODY_DETAIL, List.of());
            }
            if (status == 503) {
                return databaseUnavailable(failure, request);
            }
        }
        Problem problem = problems.problem(request, ErrorCode.INTERNAL, ApiException.INTERNAL_DETAIL, List.of());
        LOG.error("Unexpected error (traceId {})", problem.traceId(), failure);
        return ResponseEntity.status(problem.status()).contentType(PROBLEM_JSON).body(problem);
    }

    private ResponseEntity<Problem> validation(HttpServletRequest request, List<FieldError> errors) {
        return respond(request, ErrorCode.VALIDATION, ValidationFailedException.DEFAULT_DETAIL, errors);
    }

    private ResponseEntity<Problem> respond(
            HttpServletRequest request, ErrorCode code, String detail, List<FieldError> errors) {
        Problem problem = problems.problem(request, code, detail, errors);
        return ResponseEntity.status(problem.status()).contentType(PROBLEM_JSON).body(problem);
    }

    private static List<FieldError> fieldErrors(BindingResult result) {
        List<FieldError> errors = new ArrayList<>();
        result.getFieldErrors()
                .forEach(error -> errors.add(new FieldError(error.getField(), codeFor(error.getCode()))));
        result.getGlobalErrors()
                .forEach(error -> errors.add(new FieldError(error.getObjectName(), codeFor(error.getCode()))));
        return errors;
    }

    private static String code(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return codes == null || codes.length == 0 ? INVALID : codeFor(codes[codes.length - 1]);
    }

    /**
     * Maps a constraint or binding error to the LLD field code.
     *
     * @param constraint the simple name of the constraint, or the binding error code
     * @return the field code
     */
    static String codeFor(@Nullable String constraint) {
        if (constraint == null) {
            return INVALID;
        }
        return switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> FieldError.REQUIRED;
            case "Size", "Length" -> FieldError.LENGTH;
            case "Pattern" -> FieldError.PATTERN;
            case "Email", "typeMismatch" -> FieldError.FORMAT;
            case "Min",
                    "Max",
                    "DecimalMin",
                    "DecimalMax",
                    "Positive",
                    "PositiveOrZero",
                    "Negative",
                    "NegativeOrZero",
                    "Range",
                    "Past",
                    "PastOrPresent",
                    "Future",
                    "FutureOrPresent" -> FieldError.RANGE;
            default -> INVALID;
        };
    }

    private static String parameterName(MethodParameter parameter) {
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        if (param != null && !param.name().isEmpty()) {
            return param.name();
        }
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.name().isEmpty()) {
            return header.name();
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null && !path.name().isEmpty()) {
            return path.name();
        }
        String name = parameter.getParameterName();
        return name == null ? "parameter" : name;
    }

    private static String leafName(Path path) {
        String leaf = "parameter";
        for (Path.Node node : path) {
            if (node.getName() != null) {
                leaf = node.getName();
            }
        }
        return leaf;
    }

    // The JSON path of the field, for example "schedule.timezone" or "jobs[2].name"; names come from the request type.
    private static String jsonPath(DatabindException failure, @Nullable String leaf) {
        StringBuilder path = new StringBuilder();
        failure.getPath().forEach(reference -> {
            String name = reference.getPropertyName();
            if (name != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(name);
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        });
        String result = path.isEmpty() && leaf != null ? leaf : path.toString();
        return isSafeFieldPath(result) ? result : "body";
    }

    // Field paths can contain names chosen by the client (unknown fields, map keys). They are reported only when they
    // are short and made of plain identifier characters, so a response never reflects arbitrary submitted text.
    private static boolean isSafeFieldPath(String path) {
        if (path.isEmpty() || path.length() > MAX_FIELD_PATH) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '.'
                    || c == '['
                    || c == ']';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
