package dev.kairos.domain.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.TaskKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemplateEngineTest {
    private static final TaskKey PRODUCER = new TaskKey("producer");
    private static final String REF = "{{ tasks.producer.outputs.value }}";
    private static final UUID RUN = UUID.fromString("0192e7a9-1f3c-7d8e-9a0b-1c2d3e4f5a6b");

    @Test
    void recognizesOnlyTheTwoDocumentedFormsWithoutRecursiveExpansion() {
        assertThat(Template.compile("prefix {{ run.id }} " + REF).references()).hasSize(2);
        assertThat(render("x{literal}y", TemplateEngine.Destination.HEADER, context("x", false))
                        .value())
                .isEqualTo("x{literal}y");
        assertThat(render("{{run.id}}", TemplateEngine.Destination.HEADER, context("x", false))
                        .value())
                .isEqualTo(RUN.toString());
        assertThat(render(REF, TemplateEngine.Destination.HEADER, context("{{ run.id }}", false))
                        .value())
                .isEqualTo("{{ run.id }}");
        assertThat(render("", TemplateEngine.Destination.HEADER, context("x", false))
                        .value())
                .isEmpty();
        for (String invalid : List.of(
                "{{",
                "}}",
                "}}{{",
                "{{{{ run.id }}}}",
                "{{ run.id",
                "{{ env.HOME }}",
                "{{ tasks.producer.outputs['value'] }}",
                "{{ tasks.producer.outputs.value + 1 }}",
                "{{ tasks.producer.outputs.value}} }}",
                "{{ tasks." + "x".repeat(65) + ".outputs.value }}",
                "x".repeat(16385))) {
            assertThatThrownBy(() -> Template.compile(invalid))
                    .isInstanceOfSatisfying(
                            ValidationFailedException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_PLACEHOLDER));
        }
    }

    @Test
    void encodesUtf8PathSegmentsAndQueryValuesExactlyOnce() {
        var context = context("café/one?x=1&y=2", false);
        assertThat(render(REF, TemplateEngine.Destination.PATH, context).value())
                .isEqualTo("caf%C3%A9%2Fone%3Fx%3D1%26y%3D2");
        assertThat(render(REF, TemplateEngine.Destination.QUERY, context("a b+c&x=1#z", false))
                        .value())
                .isEqualTo("a%20b%2Bc%26x%3D1%23z");
        assertThat(render("abc-_.~09", TemplateEngine.Destination.PATH, context).value())
                .isEqualTo("abc-_.~09");
        assertThat(render(REF, TemplateEngine.Destination.QUERY, context("%", false))
                        .value())
                .isEqualTo("%25");
    }

    @Test
    void rejectsTraversalAndHeaderInjectionWithoutLeakingTheValue() {
        for (String path :
                List.of(".", "..", "../secret", "a/../secret", "..\\secret", "%2e%2e%2fsecret", "%252esecret")) {
            renderFails(() -> render(REF, TemplateEngine.Destination.PATH, context(path, false)));
        }
        for (String header : List.of("secret\r\nX-Injected: true", "secret\n", "secret\u0000", "secret\u007f")) {
            assertThatThrownBy(() -> render(REF, TemplateEngine.Destination.HEADER, context(header, false)))
                    .isInstanceOf(ValidationFailedException.class)
                    .hasMessageNotContaining("secret");
        }
        renderFails(() -> render("literal\r\nheader", TemplateEngine.Destination.HEADER, context("x", false)));
    }

    @Test
    void fixedHostAndSchemeNeverAcceptPlaceholders() {
        for (TemplateEngine.Destination site :
                List.of(TemplateEngine.Destination.HOST, TemplateEngine.Destination.SCHEME)) {
            renderFails(() -> render("{{run.id}}", site, context("x", false)));
            assertThatThrownBy(() -> TemplateEngine.validate(
                            Template.compile(REF),
                            site,
                            new TaskKey("consumer"),
                            Set.of(PRODUCER),
                            Map.of(
                                    PRODUCER,
                                    Map.of("value", new OutputSpec(OutputSpec.Source.STATUS, "", true, false)))))
                    .isInstanceOf(ValidationFailedException.class);
        }
        assertThat(render("internal.example", TemplateEngine.Destination.HOST, context("x", false))
                        .value())
                .isEqualTo("internal.example");
        assertThat(render("https", TemplateEngine.Destination.SCHEME, context("x", false))
                        .value())
                .isEqualTo("https");
    }

    @Test
    void typedJsonPlaceholdersCannotInjectPropertiesOrChangeAnEnclosingString() {
        for (Object value : List.of(12, true, Map.of("number", 3), List.of(1, 2))) {
            var context = context(value, false);
            assertThat(TemplateEngine.renderJson(JsonValue.of(REF), context).value())
                    .isEqualTo(JsonValue.of(value));
        }
        assertThat(TemplateEngine.renderJson(JsonValue.of(REF), context(null, false))
                        .value())
                .isEqualTo(JsonValue.NullValue.INSTANCE);
        String attack = "\"},\"admin\":true,\"x\":\"";
        JsonValue.ObjectValue result = (JsonValue.ObjectValue)
                TemplateEngine.renderJson(JsonValue.of(Map.of("data", "prefix-" + REF)), context(attack, false))
                        .value();
        assertThat(result.values()).containsOnlyKeys("data");
        assertThat(((JsonValue.TextValue) result.values().get("data")).value()).isEqualTo("prefix-" + attack);
        assertThat(result.json()).contains("\\\"admin\\\"");
        renderFails(() -> TemplateEngine.renderJson(JsonValue.of("prefix-" + REF), context(List.of(1, 2), false)));
        renderFails(() -> TemplateEngine.renderJson(JsonValue.of("prefix-" + REF), context(Map.of("a", 1), false)));
        renderFails(() ->
                TemplateEngine.renderJson(JsonValue.of(Map.of("{{run.id}}", "not allowed")), context("x", false)));
        assertThat(TemplateEngine.renderJson(JsonValue.of(Map.of("items", List.of(REF), "fixed", 3)), context(5, false))
                        .value()
                        .json())
                .isEqualTo("{\"fixed\":3,\"items\":[5]}");
    }

    @Test
    void sensitiveValuesStayOutOfUrlsAndPropagateToTheRenderedRequest() {
        var context = context("canary-sensitive-token", true);
        for (TemplateEngine.Destination destination :
                List.of(TemplateEngine.Destination.PATH, TemplateEngine.Destination.QUERY)) {
            renderFails(() -> render(REF, destination, context));
        }
        var request = new RequestTemplate(
                Map.of("id", "{{run.id}}"),
                Map.of("q", "plain text"),
                Map.of("Authorization", "Bearer " + REF),
                JsonValue.of(Map.of("secret", REF)));
        var result = TemplateEngine.renderRequest(request, context);
        assertThat(result.sensitive()).isTrue();
        assertThat(result.headers().get("Authorization")).isEqualTo("Bearer canary-sensitive-token");
        assertThat(result.query().get("q")).isEqualTo("plain%20text");
        for (Object diagnostic : List.of(
                context,
                result,
                request,
                Template.compile(REF),
                render(REF, TemplateEngine.Destination.HEADER, context),
                TemplateEngine.renderJson(JsonValue.of(REF), context))) {
            assertThat(diagnostic.toString()).doesNotContain("canary-sensitive-token");
        }
        assertThatThrownBy(() -> result.headers().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(TemplateEngine.renderRequest(RequestTemplate.empty(), context)
                        .body())
                .isNull();
        assertThat(TemplateEngine.renderRequest(RequestTemplate.empty(), context)
                        .sensitive())
                .isFalse();
    }

    @Test
    void missingValuesAndExpansionLimitsFailClosed() {
        var empty = new TemplateEngine.Context(RUN, Map.of());
        renderFails(() -> render(REF, TemplateEngine.Destination.HEADER, empty));
        var missing = new TemplateEngine.Context(RUN, Map.of(PRODUCER, Map.of()));
        renderFails(() -> render(REF, TemplateEngine.Destination.HEADER, missing));
        renderFails(() -> render(REF.repeat(200), TemplateEngine.Destination.BODY, context("x".repeat(1000), false)));
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) body.put("value" + i, REF);
        renderFails(() -> TemplateEngine.renderJson(JsonValue.of(body), context("x".repeat(12000), false)));
        assertThatThrownBy(() -> context("x".repeat(25000), false)).isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void scalarInterpolationPreservesJsonNumberBooleanAndNullSpelling() {
        assertThat(render("n=" + REF, TemplateEngine.Destination.HEADER, context(3, false))
                        .value())
                .isEqualTo("n=3");
        assertThat(render("b=" + REF, TemplateEngine.Destination.HEADER, context(false, false))
                        .value())
                .isEqualTo("b=false");
        assertThat(render("n=" + REF, TemplateEngine.Destination.HEADER, context(null, false))
                        .value())
                .isEqualTo("n=null");
    }

    private static TemplateEngine.Context context(Object value, boolean sensitive) {
        return new TemplateEngine.Context(
                RUN, Map.of(PRODUCER, Map.of("value", new OutputValue(JsonValue.of(value), sensitive))));
    }

    private static TemplateEngine.RenderedText render(
            String text, TemplateEngine.Destination destination, TemplateEngine.Context context) {
        return TemplateEngine.renderText(Template.compile(text), destination, context);
    }

    private static void renderFails(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        ValidationFailedException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.RENDER_FAILED));
    }
}
