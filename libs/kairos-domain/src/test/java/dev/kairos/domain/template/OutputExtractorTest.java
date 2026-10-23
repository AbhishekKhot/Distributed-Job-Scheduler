package dev.kairos.domain.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.ErrorClass;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutputExtractorTest {
    @Test
    void extractsNestedBodyCaseInsensitiveHeadersAndNumericStatusWithSensitivity() {
        JsonValue body = JsonValue.of(Map.of("data", Map.of("items", List.of(Map.of("id", 7)))));
        Map<String, OutputValue> outputs = OutputExtractor.extract(
                Map.of(
                        "id", spec(OutputSpec.Source.BODY, "$.data.items[0].id", true, false),
                        "token", spec(OutputSpec.Source.HEADER, "x-token", true, true),
                        "status", spec(OutputSpec.Source.STATUS, "", true, false)),
                body,
                Map.of("X-Token", List.of("canary")),
                201);
        assertThat(outputs.get("id").value()).isEqualTo(JsonValue.of(7));
        assertThat(outputs.get("status").value()).isEqualTo(JsonValue.of(201));
        assertThat(outputs.get("token").sensitive()).isTrue();
        assertThat(outputs.get("token").toString()).doesNotContain("canary");
        assertThatThrownBy(outputs::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missingOptionalDataIsOmittedAndExplicitNullIsPresent() {
        var optional = spec(OutputSpec.Source.BODY, "$.missing[3]", false, false);
        assertThat(OutputExtractor.extract(Map.of("value", optional), null, Map.of(), 200))
                .isEmpty();
        assertThat(OutputExtractor.extract(
                        Map.of("value", optional), JsonValue.of(Map.of("missing", List.of())), Map.of(), 200))
                .isEmpty();
        assertThat(OutputExtractor.extract(
                        Map.of("value", optional), JsonValue.of(Map.of("missing", 4)), Map.of(), 200))
                .isEmpty();
        assertThat(OutputExtractor.extract(
                                Map.of("value", spec(OutputSpec.Source.BODY, "$", true, false)),
                                JsonValue.of(null),
                                Map.of(),
                                200)
                        .get("value")
                        .value())
                .isEqualTo(JsonValue.NullValue.INSTANCE);
        assertThat(OutputExtractor.extract(
                        Map.of("value", spec(OutputSpec.Source.HEADER, "X-Missing", false, false)),
                        JsonValue.of(null),
                        Map.of(),
                        200))
                .isEmpty();
    }

    @Test
    void requiredMissingAndAmbiguousHeadersFailWithoutResponseContents() {
        fails(
                () -> OutputExtractor.extract(
                        Map.of("value", spec(OutputSpec.Source.BODY, "$.missing", true, false)),
                        JsonValue.of(Map.of()),
                        Map.of(),
                        200),
                ErrorClass.OUTPUT_MISSING);
        fails(
                () -> OutputExtractor.extract(
                        Map.of("value", spec(OutputSpec.Source.HEADER, "X-Token", true, true)),
                        null,
                        Map.of("X-Token", List.of("canary", "other")),
                        200),
                ErrorClass.OUTPUT_MISSING);
        fails(
                () -> OutputExtractor.extract(
                        Map.of("value", spec(OutputSpec.Source.HEADER, "X-Token", true, true)),
                        null,
                        Map.of("X-Token", List.of("canary"), "x-token", List.of("other")),
                        200),
                ErrorClass.OUTPUT_MISSING);
        fails(() -> OutputExtractor.extract(Map.of(), null, Map.of(), 99), ErrorClass.OUTPUT_MISSING);
        fails(
                () -> OutputExtractor.extract(
                        Map.of("bad.name", spec(OutputSpec.Source.STATUS, "", false, false)), null, Map.of(), 200),
                ErrorClass.OUTPUT_MISSING);
    }

    @Test
    void countsAllSerializedOutputBytesIncludingSensitiveValuesAndNames() {
        var root = spec(OutputSpec.Source.BODY, "$", true, true);
        // {"data":"..."} adds eleven ASCII bytes.
        assertThat(OutputExtractor.extract(Map.of("data", root), JsonValue.of("x".repeat(24576 - 11)), Map.of(), 200))
                .hasSize(1);
        fails(
                () -> OutputExtractor.extract(
                        Map.of("data", root), JsonValue.of("x".repeat(24576 - 10)), Map.of(), 200),
                ErrorClass.RESPONSE_TOO_LARGE);
        fails(
                () -> OutputExtractor.extract(
                        Map.of("a", root, "b", root), JsonValue.of("é".repeat(7000)), Map.of(), 200),
                ErrorClass.RESPONSE_TOO_LARGE);
        fails(
                () -> OutputExtractor.extract(
                        Map.of("data", root),
                        new JsonValue.ArrayValue(java.util.Collections.nCopies(100, JsonValue.of("x".repeat(12000)))),
                        Map.of(),
                        200),
                ErrorClass.RESPONSE_TOO_LARGE);
    }

    @Test
    void selectorGrammarRejectsExpressionsWildcardsInvalidIndexesAndBadHeaders() {
        for (String path : List.of(
                "",
                "data.id",
                "$..id",
                "$.a[*]",
                "$.a[-1]",
                "$.a[",
                "$.a[]",
                "$.a[1234567890]",
                "$.a[no]",
                "$.a]",
                "$.a b",
                "$" + ".a".repeat(33),
                "$" + "x".repeat(256))) {
            assertThatThrownBy(() -> spec(OutputSpec.Source.BODY, path, true, false))
                    .isInstanceOf(ValidationFailedException.class);
        }
        assertThatThrownBy(() -> spec(OutputSpec.Source.HEADER, "X\r\nHeader", true, false))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> spec(OutputSpec.Source.STATUS, "not-empty", true, false))
                .isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> new OutputExtractionException(ErrorClass.TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OutputSpec spec(OutputSpec.Source source, String path, boolean required, boolean sensitive) {
        return new OutputSpec(source, path, required, sensitive);
    }

    private static void fails(Runnable operation, ErrorClass code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(OutputExtractionException.class, failure -> {
            assertThat(failure.errorClass()).isEqualTo(code);
            assertThat(failure.getMessage()).doesNotContain("canary");
        });
    }
}
