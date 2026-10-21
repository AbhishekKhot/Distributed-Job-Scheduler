package dev.kairos.api.support.problem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.ProbeApplicationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every error mapping of the API, over real HTTP: problem bodies, status codes, LLD codes and headers. */
class ProblemMappingIntegrationTest extends ProbeApplicationTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private String token;

    @BeforeEach
    void caller() {
        token = newUser(ApiTestFixtures.insertTeam()).token();
    }

    @Test
    void invalidBodyFieldsAreReportedWithStableCodesAndNoValues() {
        HttpResponse<String> response =
                send("POST", "/v1/probe/validated", token, "{\"name\": \"far-too-long-name\", \"count\": 9}");

        JsonNode problem = problem(response, 400, "validation");
        assertThat(problem.path("detail").asString()).isEqualTo("One or more fields are invalid.");
        assertThat(problem.path("errors").toString())
                .contains("{\"field\":\"name\",\"code\":\"length\"}")
                .contains("{\"field\":\"count\",\"code\":\"range\"}");
        assertThat(response.body()).doesNotContain("far-too-long-name");
    }

    @Test
    void aMissingRequiredFieldIsRequired() {
        JsonNode problem = problem(send("POST", "/v1/probe/validated", token, "{}"), 400, "validation");

        assertThat(problem.path("errors").toString()).contains("{\"field\":\"name\",\"code\":\"required\"}");
    }

    @Test
    void unknownFieldsAreRejected() {
        JsonNode problem = problem(
                send("POST", "/v1/probe/validated", token, "{\"name\": \"a\", \"teamId\": \"x\"}"), 400, "validation");

        assertThat(problem.path("errors").toString()).contains("{\"field\":\"teamId\",\"code\":\"unknown-field\"}");
    }

    @Test
    void unknownFieldNamesThatAreNotIdentifiersAreNotEchoed() {
        HttpResponse<String> response =
                send("POST", "/v1/probe/validated", token, "{\"name\": \"a\", \"<script>\": 1}");

        JsonNode problem = problem(response, 400, "validation");
        assertThat(problem.path("errors").toString()).contains("{\"field\":\"body\",\"code\":\"unknown-field\"}");
        assertThat(response.body()).doesNotContain("script");
    }

    @Test
    void malformedJsonIsRejected() {
        JsonNode problem = problem(send("POST", "/v1/probe/validated", token, "{\"name\": "), 400, "validation");

        assertThat(problem.path("detail").asString()).isEqualTo(ProblemDetailsAdvice.UNREADABLE_BODY_DETAIL);
    }

    @Test
    void aWrongJsonTypeNamesTheField() {
        JsonNode problem = problem(
                send("POST", "/v1/probe/validated", token, "{\"name\": \"a\", \"count\": \"x\"}"), 400, "validation");

        assertThat(problem.path("errors").toString()).contains("{\"field\":\"count\",\"code\":\"format\"}");
    }

    @Test
    void anUnsupportedMediaTypeIsRejected() {
        HttpResponse<String> response = send("POST", "/v1/probe/validated", token, null, "Content-Type", "text/plain");

        JsonNode problem = problem(response, 400, "validation");
        assertThat(problem.path("errors").toString()).contains("Content-Type");
    }

    @Test
    void parameterConstraintsAreValidated() {
        JsonNode problem = problem(send("GET", "/v1/probe/limited?count=9", token, null), 400, "validation");

        assertThat(problem.path("errors").toString()).contains("{\"field\":\"count\",\"code\":\"range\"}");
    }

    @Test
    void missingParametersAndHeadersAreRequired() {
        assertThat(problem(send("GET", "/v1/probe/limited", token, null), 400, "validation")
                        .path("errors")
                        .toString())
                .contains("{\"field\":\"count\",\"code\":\"required\"}");
        assertThat(problem(send("GET", "/v1/probe/header", token, null), 400, "validation")
                        .path("errors")
                        .toString())
                .contains("{\"field\":\"X-Probe\",\"code\":\"required\"}");
    }

    @Test
    void aMalformedPathValueIsAFormatError() {
        JsonNode problem = problem(send("GET", "/v1/probe/typed/not-a-uuid", token, null), 400, "validation");

        assertThat(problem.path("errors").toString()).contains("{\"field\":\"id\",\"code\":\"format\"}");
    }

    @Test
    void domainExceptionsKeepTheirCodeAndDetail() {
        JsonNode conflict = problem(send("GET", "/v1/probe/conflict", token, null), 409, "job-name-taken");
        assertThat(conflict.path("title").asString()).isEqualTo("Job name taken");
        assertThat(conflict.path("detail").asString()).isEqualTo("A job with this name already exists in the team.");

        JsonNode missing = problem(send("GET", "/v1/probe/missing", token, null), 404, "not-found");
        assertThat(missing.path("detail").asString()).isEqualTo("The job does not exist.");
    }

    @Test
    void unexpectedErrorsAreGenericAndCarryTheTraceId() {
        HttpResponse<String> response = send("GET", "/v1/probe/boom", token, null);

        JsonNode problem = problem(response, 500, "internal");
        assertThat(problem.path("detail").asString())
                .isEqualTo("An unexpected error occurred. Quote the traceId when reporting it.");
        assertThat(problem.path("traceId").asString()).matches("[0-9a-f]{16,32}");
        assertThat(response.body()).doesNotContain("must not leak").doesNotContain("IllegalStateException");
    }

    @Test
    void anUnreachableDatabaseIsUnavailableWithRetryAfter() {
        HttpResponse<String> response = send("GET", "/v1/probe/database-down", token, null);

        problem(response, 503, "unavailable");
        assertThat(response.headers().firstValue("Retry-After")).contains("5");
    }

    @Test
    void unknownPathsAndMethodsAreNotFound() {
        problem(send("GET", "/v1/no-such-resource", token, null), 404, "not-found");
        problem(send("DELETE", "/v1/probe/conflict", token, null), 404, "not-found");
    }

    @Test
    void ifMatchIsRequiredAndChecked() {
        JsonNode missing = problem(send("PUT", "/v1/probe/versioned/x", token, "{}"), 428, "if-match-required");
        assertThat(missing.path("detail").asString()).isEqualTo("Send the current version in the If-Match header.");

        problem(send("PUT", "/v1/probe/versioned/x", token, "{}", "If-Match", "W/\"3\""), 412, "version-mismatch");
        problem(send("PUT", "/v1/probe/versioned/x", token, "{}", "If-Match", "\"2\""), 412, "version-mismatch");

        HttpResponse<String> ok = send("PUT", "/v1/probe/versioned/x", token, "{}", "If-Match", "\"3\"");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("ETag")).contains("\"4\"");
    }

    @Test
    void cursorsRoundTripAndTamperedCursorsAreRejected() {
        HttpResponse<String> first = send("GET", "/v1/probe/page", token, null);
        assertThat(first.statusCode()).isEqualTo(200);
        String cursor = json.readTree(first.body()).path("nextCursor").asString();

        HttpResponse<String> next = send("GET", "/v1/probe/page?limit=10&cursor=" + cursor, token, null);
        assertThat(next.statusCode()).isEqualTo(200);
        assertThat(next.body()).contains("size=10").contains("after=[k1, k2]");

        char last = cursor.charAt(cursor.length() - 1);
        String tampered = cursor.substring(0, cursor.length() - 1) + (last == 'A' ? 'B' : 'A');
        JsonNode problem = problem(send("GET", "/v1/probe/page?cursor=" + tampered, token, null), 400, "validation");
        assertThat(problem.path("errors").toString()).contains("{\"field\":\"cursor\",\"code\":\"format\"}");

        String otherTeam = newUser(ApiTestFixtures.insertTeam()).token();
        problem(send("GET", "/v1/probe/page?cursor=" + cursor, otherTeam, null), 400, "validation");
        problem(send("GET", "/v1/probe/page?limit=201", token, null), 400, "validation");
    }

    private JsonNode problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode problem = json.readTree(response.body());
        assertThat(problem.path("type").asString()).isEqualTo("/errors/" + code);
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("instance").asString()).startsWith("/v1/");
        assertThat(problem.path("traceId").asString()).isNotBlank();
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        return problem;
    }
}
