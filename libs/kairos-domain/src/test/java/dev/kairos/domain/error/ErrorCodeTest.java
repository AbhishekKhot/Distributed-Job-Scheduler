package dev.kairos.domain.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    /** A problem body in the LLD: {"type": "/errors/x", "title": "...", "status": n. */
    private static final Pattern PROBLEM =
            Pattern.compile("\\{\"type\": \"/errors/([a-z-]+)\", \"title\": \"([^\"]+)\", \"status\": (\\d{3})");

    /** Codes in the LLD's list of common errors (500 and 503) that never appear in an example body. */
    private static final Pattern COMMON_ERROR = Pattern.compile("\\| `(\\d{3})` \\| `([a-z-]+)` \\|");

    @Test
    void catalogueMatchesEveryProblemTypeInTheLld() throws IOException {
        String lld = Files.readString(Path.of("../../LLD.md"), StandardCharsets.UTF_8);
        Map<String, String> documented = new LinkedHashMap<>();
        Matcher problem = PROBLEM.matcher(lld);
        while (problem.find()) {
            documented.put(problem.group(1), problem.group(2) + " " + problem.group(3));
        }
        Matcher common = COMMON_ERROR.matcher(lld);
        while (common.find()) {
            documented.putIfAbsent(common.group(2), common.group(1));
        }

        assertThat(documented).isNotEmpty();
        documented.forEach((slug, titleAndStatus) -> {
            ErrorCode code = ErrorCode.fromSlug(slug).orElseThrow(() -> new AssertionError("Missing " + slug));
            assertThat(titleAndStatus).endsWith(Integer.toString(code.status()));
            if (titleAndStatus.contains(" ")) {
                assertThat(titleAndStatus).isEqualTo(code.title() + " " + code.status());
            }
        });
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::slug))
                .containsExactlyInAnyOrderElementsOf(documented.keySet());
    }

    @Test
    void typesUseTheErrorsPrefix() {
        assertThat(ErrorCode.NOT_FOUND.type()).isEqualTo("/errors/not-found");
        assertThat(ErrorCode.fromSlug("no-such-code")).isEmpty();
    }
}
