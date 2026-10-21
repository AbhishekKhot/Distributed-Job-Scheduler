package dev.kairos.api.support.problem;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An RFC 9457 problem body as the LLD defines it (section 3.1).
 *
 * <p>Every field is a fixed, client-safe value: the stable type and title of the {@link ErrorCode}, a fixed detail
 * sentence, the request path and the trace id. Submitted values never appear, and {@code errors} lists only field
 * paths with stable codes.
 *
 * @param type the problem type, for example {@code /errors/not-found}
 * @param title the problem title
 * @param status the HTTP status
 * @param detail the fixed detail sentence
 * @param instance the request path
 * @param traceId the trace id to quote when reporting the error
 * @param errors the invalid fields, or {@code null} when the problem is not about fields
 */
@Schema(name = "Problem", description = "RFC 9457 problem details.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
        @Schema(example = "/errors/validation") String type,
        @Schema(example = "Invalid request") String title,
        @Schema(example = "400") int status,
        @Schema(example = "One or more fields are invalid.") String detail,
        @Schema(example = "/v1/jobs") String instance,

        @Schema(example = "4bf92f3577b34da6a3ce929d0e0e4736")
        String traceId,

        @Nullable List<FieldError> errors) {

    /** The media type of problem bodies. */
    public static final String MEDIA_TYPE = "application/problem+json";

    /** Validates the components and copies the field errors. */
    public Problem {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(traceId, "traceId");
        errors = errors == null || errors.isEmpty() ? null : List.copyOf(errors);
    }

    /**
     * Builds the problem for an error code.
     *
     * @param code the error code, which gives the type, title and status
     * @param detail the fixed detail sentence
     * @param instance the request path
     * @param traceId the trace id
     * @param errors the invalid fields, possibly empty
     * @return the problem
     */
    public static Problem of(ErrorCode code, String detail, String instance, String traceId, List<FieldError> errors) {
        return new Problem(code.type(), code.title(), code.status(), detail, instance, traceId, errors);
    }
}
