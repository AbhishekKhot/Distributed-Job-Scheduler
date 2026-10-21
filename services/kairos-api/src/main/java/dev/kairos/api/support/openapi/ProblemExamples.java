package dev.kairos.api.support.openapi;

import dev.kairos.api.support.problem.Problem;
import dev.kairos.domain.error.ErrorCode;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the documented problem responses and their examples. */
final class ProblemExamples {

    /** The schema reference of a problem body. */
    static final String PROBLEM_SCHEMA_REF = "#/components/schemas/Problem";

    private static final String EXAMPLE_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private ProblemExamples() {}

    /**
     * Adds a problem example to the response of a status, creating the response if the operation does not document
     * it yet.
     *
     * @param responses the operation's responses
     * @param code the error code, which gives the status
     * @param detail the example detail
     * @param description the response description if it is created
     */
    static void add(ApiResponses responses, ErrorCode code, String detail, String description) {
        String status = Integer.toString(code.status());
        ApiResponse response = responses.get(status);
        if (response == null) {
            response = new ApiResponse().description(description);
            responses.addApiResponse(status, response);
        }
        Content content = response.getContent();
        if (content == null) {
            content = new Content();
            response.setContent(content);
        }
        MediaType media = content.get(Problem.MEDIA_TYPE);
        if (media == null) {
            media = new MediaType().schema(new Schema<>().$ref(PROBLEM_SCHEMA_REF));
            content.addMediaType(Problem.MEDIA_TYPE, media);
        }
        if (media.getExamples() == null || !media.getExamples().containsKey(code.slug())) {
            media.addExamples(code.slug(), new Example().summary(code.title()).value(example(code, detail)));
        }
        if (code.status() == 429 || code.status() == 503) {
            response.addHeaderObject(
                    "Retry-After",
                    new Header().description("Seconds to wait before retrying.").schema(new IntegerSchema()));
        }
    }

    private static Map<String, Object> example(ErrorCode code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", code.type());
        body.put("title", code.title());
        body.put("status", code.status());
        body.put("detail", detail);
        body.put("instance", "/v1/jobs");
        body.put("traceId", EXAMPLE_TRACE_ID);
        return body;
    }
}
