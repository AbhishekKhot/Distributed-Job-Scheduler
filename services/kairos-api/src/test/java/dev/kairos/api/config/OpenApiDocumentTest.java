package dev.kairos.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.testsupport.RepositoryFiles;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exports the OpenAPI document of the running application to {@code build/openapi/kairos-v1.json} and checks it
 * against the 68 operations of LLD section 3 (IMPLEMENTATION_CHECKLIST section 4.5).
 *
 * <p>The operations are read from the LLD itself. An operation whose task has not been merged yet is listed in
 * {@link #NOT_YET_IMPLEMENTED}; it must be absent, and every other operation must be present. A task that adds
 * operations removes their numbers from that set, so the list can only shrink and a missing endpoint fails the build.
 */
class OpenApiDocumentTest extends ApiApplicationTest {

    /**
     * LLD operation numbers whose feature tasks are not implemented yet. T-207 implements 59–64 and 66; redrive,
     * bulk redrive and stats remain pending.
     */
    static final Set<Integer> NOT_YET_IMPLEMENTED =
            Set.copyOf(IntStream.rangeClosed(25, 58).boxed().toList());

    private static final Pattern OPERATION = Pattern.compile(
            "^<summary><b>(\\d{1,2})\\. [^<]{1,80}</b> &nbsp; <code>(GET|POST|PUT|PATCH|DELETE) (/[^<\\s]{1,120})</code></summary>$");

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void exportsTheDocumentWithEveryImplementedLldOperation() throws IOException {
        HttpResponse<String> response = send("GET", "/v3/api-docs/v1", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        export(response.body());

        JsonNode document = json.readTree(response.body());
        assertThat(document.path("info").path("title").asString()).isEqualTo("Kairos API");
        assertThat(document.path("components").path("securitySchemes").has(OpenApiConfig.BEARER_AUTH))
                .isTrue();
        assertThat(document.path("components").path("schemas").has("Problem")).isTrue();
        assertThat(document.path("servers").path(0).path("url").asString()).isEqualTo("/");

        List<LldOperation> operations = lldOperations();
        assertThat(operations).hasSize(68);
        assertThat(operations.stream().map(LldOperation::number)).doesNotHaveDuplicates();
        for (LldOperation operation : operations) {
            boolean documented = document.path("paths")
                    .path(operation.path())
                    .has(operation.method().toLowerCase(Locale.ROOT));
            if (NOT_YET_IMPLEMENTED.contains(operation.number())) {
                assertThat(documented)
                        .as(
                                "LLD operation %d %s %s is implemented; remove it from NOT_YET_IMPLEMENTED",
                                operation.number(), operation.method(), operation.path())
                        .isFalse();
            } else {
                assertThat(documented)
                        .as(
                                "LLD operation %d %s %s is missing from the OpenAPI document",
                                operation.number(), operation.method(), operation.path())
                        .isTrue();
            }
        }
    }

    private static List<LldOperation> lldOperations() throws IOException {
        List<LldOperation> operations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : Files.readAllLines(RepositoryFiles.resolve("LLD.md"), StandardCharsets.UTF_8)) {
            Matcher matcher = OPERATION.matcher(line.strip());
            if (matcher.matches()) {
                LldOperation operation =
                        new LldOperation(Integer.parseInt(matcher.group(1)), matcher.group(2), matcher.group(3));
                assertThat(seen.add(operation.method() + " " + operation.path()))
                        .as("duplicate LLD operation %s", operation)
                        .isTrue();
                operations.add(operation);
            }
        }
        return operations;
    }

    private static void export(String document) throws IOException {
        String output = System.getProperty("kairos.openapi.output");
        if (output == null) {
            return;
        }
        Path file = Path.of(output);
        Files.createDirectories(file.getParent());
        Files.writeString(file, document, StandardCharsets.UTF_8);
    }

    private record LldOperation(int number, String method, String path) {}
}
