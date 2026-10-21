package dev.kairos.api.support.openapi;

import dev.kairos.api.security.TeamContext;
import dev.kairos.api.security.UserPrincipal;
import dev.kairos.api.security.web.UserTokenOnlyInterceptor;
import dev.kairos.api.support.etag.ETags;
import dev.kairos.api.support.etag.IfMatch;
import dev.kairos.api.support.idempotency.IdempotencyFilter;
import dev.kairos.api.support.idempotency.IdempotencyInterceptor;
import dev.kairos.api.support.idempotency.Idempotent;
import dev.kairos.api.support.problem.ApiException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.PreconditionFailedException;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Arrays;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.HandlerMethod;

/**
 * Adds what every operation shares to the OpenAPI document, so controllers document only their own responses
 * (IMPLEMENTATION_CHECKLIST section 4.5).
 *
 * <ul>
 *   <li>429 {@code rate-limited}/{@code quota-exceeded}, 500 {@code internal} and 503 {@code unavailable} on every
 *       operation, with {@code Retry-After} on 429 and 503;
 *   <li>403 {@code no-team} on operations that take a {@link TeamContext}, and 403 {@code forbidden-for-api-key} on
 *       operations reserved for user tokens;
 *   <li>the optional {@code Idempotency-Key} header on {@link Idempotent} operations;
 *   <li>the required {@code If-Match} header, with 412 and 428, on operations with an {@link IfMatch} parameter.
 * </ul>
 *
 * 401 depends on the path, so {@link PublicOperationsCustomizer} adds it.
 */
public final class CommonResponsesCustomizer implements GlobalOperationCustomizer {

    /** LLD message of 412 {@code version-mismatch}. */
    static final String VERSION_MISMATCH_DETAIL =
            PreconditionFailedException.versionMismatch().detail();

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }
        if (hasParameter(handlerMethod, TeamContext.class)) {
            ProblemExamples.add(responses, ErrorCode.NO_TEAM, ApiException.NO_TEAM_DETAIL, "Forbidden");
        }
        if (UserTokenOnlyInterceptor.isUserTokenOnly(handlerMethod)
                || hasParameter(handlerMethod, UserPrincipal.class)) {
            ProblemExamples.add(
                    responses, ErrorCode.FORBIDDEN_FOR_API_KEY, ApiException.FORBIDDEN_FOR_API_KEY_DETAIL, "Forbidden");
        }
        if (handlerMethod.hasMethodAnnotation(Idempotent.class)) {
            operation.addParametersItem(new Parameter()
                    .in("header")
                    .name(IdempotencyFilter.HEADER)
                    .required(false)
                    .description("Makes the create idempotent for 24 hours: a repeat with the same body returns the"
                            + " first response; a different body returns 409 idempotency-key-reused.")
                    .schema(new StringSchema().minLength(1).maxLength(IdempotencyInterceptor.MAX_KEY_LENGTH)));
        }
        if (Arrays.stream(handlerMethod.getMethodParameters())
                .anyMatch(parameter -> parameter.hasParameterAnnotation(IfMatch.class))) {
            operation.addParametersItem(new Parameter()
                    .in("header")
                    .name("If-Match")
                    .required(true)
                    .description("The current version of the resource, as returned in its ETag.")
                    .example("\"3\"")
                    .schema(new StringSchema().maxLength(21)));
            ProblemExamples.add(responses, ErrorCode.VERSION_MISMATCH, VERSION_MISMATCH_DETAIL, "Precondition failed");
            ProblemExamples.add(
                    responses, ErrorCode.IF_MATCH_REQUIRED, ETags.IF_MATCH_REQUIRED_DETAIL, "Precondition required");
        }
        ProblemExamples.add(responses, ErrorCode.RATE_LIMITED, ApiException.RATE_LIMITED_DETAIL, "Too many requests");
        ProblemExamples.add(responses, ErrorCode.INTERNAL, ApiException.INTERNAL_DETAIL, "Internal error");
        ProblemExamples.add(responses, ErrorCode.UNAVAILABLE, ApiException.UNAVAILABLE_DETAIL, "Service unavailable");
        return operation;
    }

    private static boolean hasParameter(HandlerMethod method, Class<?> type) {
        for (MethodParameter parameter : method.getMethodParameters()) {
            if (parameter.getParameterType() == type) {
                return true;
            }
        }
        return false;
    }
}
