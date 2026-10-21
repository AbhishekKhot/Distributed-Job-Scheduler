package dev.kairos.api.support.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ProbeApplicationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The common responses and headers the customizers add, checked on the probe operations. */
class OpenApiConventionsIntegrationTest extends ProbeApplicationTest {

    private JsonNode paths;

    @BeforeEach
    void document() {
        HttpResponse<String> response = send("GET", "/v3/api-docs/v1", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        paths = JsonMapper.builder().build().readTree(response.body()).path("paths");
    }

    @Test
    void everyOperationDocumentsTheCommonErrors() {
        JsonNode responses = paths.path("/v1/probe/team").path("get").path("responses");

        for (String status : new String[] {"401", "403", "429", "500", "503"}) {
            assertThat(responses.has(status)).as(status).isTrue();
            assertThat(responses.path(status).path("content").has("application/problem+json"))
                    .isTrue();
        }
        assertThat(responses.path("403").toString()).contains("/errors/no-team");
        assertThat(responses.path("429").path("headers").has("Retry-After")).isTrue();
        assertThat(responses.path("503").path("headers").has("Retry-After")).isTrue();
        assertThat(responses.path("401").toString()).contains("/errors/unauthenticated");
    }

    @Test
    void credentialParametersAreNotRequestParameters() {
        JsonNode operation = paths.path("/v1/probe/team").path("get");

        assertThat(operation.path("parameters").isMissingNode()
                        || operation.path("parameters").isEmpty())
                .isTrue();
    }

    @Test
    void userTokenOnlyOperationsDocumentTheApiKeyRefusal() {
        assertThat(paths.path("/v1/probe/user-only")
                        .path("post")
                        .path("responses")
                        .path("403")
                        .toString())
                .contains("/errors/forbidden-for-api-key")
                .contains("/errors/no-team");
    }

    @Test
    void idempotentOperationsDocumentTheKeyHeader() {
        JsonNode parameters = paths.path("/v1/probe/jobs").path("post").path("parameters");

        assertThat(parameters.toString())
                .contains("\"name\":\"Idempotency-Key\"")
                .contains("\"in\":\"header\"");
        assertThat(paths.path("/v1/probe/batch").path("post").path("parameters").toString())
                .contains("Idempotency-Key");
        assertThat(paths.path("/v1/probe/team").path("get").toString()).doesNotContain("Idempotency-Key");
    }

    @Test
    void versionedUpdatesDocumentIfMatch() {
        JsonNode operation = paths.path("/v1/probe/versioned/{id}").path("put");

        assertThat(operation.path("parameters").toString())
                .contains("\"name\":\"If-Match\"")
                .contains("\"required\":true")
                .doesNotContain("\"name\":\"version\"");
        assertThat(operation.path("responses").has("412")).isTrue();
        assertThat(operation.path("responses").has("428")).isTrue();
    }
}
