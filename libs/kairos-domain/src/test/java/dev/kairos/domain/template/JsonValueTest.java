package dev.kairos.domain.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.domain.error.ValidationFailedException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonValueTest {
    @Test
    void serializesAllScalarTypesAndEscapesJsonWithoutReflection() {
        JsonValue value = JsonValue.of(Map.of(
                "values",
                Arrays.asList(
                        null,
                        true,
                        (byte) 1,
                        (short) 2,
                        3,
                        4L,
                        1.25f,
                        2.5,
                        new BigDecimal("3.50"),
                        BigInteger.valueOf(9)),
                "text",
                "\"\\\b\f\n\r\t\u0001é"));
        assertThat(value.json())
                .isEqualTo(
                        "{\"text\":\"\\\"\\\\\\b\\f\\n\\r\\t\\u0001é\",\"values\":[null,true,1,2,3,4,1.25,2.5,3.50,9]}");
    }

    @Test
    void rejectsNonJsonTypesNonFiniteNumbersMalformedUnicodeAndUnboundedDecimals() {
        for (Object value : List.of(
                new Object(),
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
                Map.of(1, "bad key"),
                new BigDecimal(BigInteger.ONE, 9000))) {
            assertThatThrownBy(() -> JsonValue.of(value)).isInstanceOf(ValidationFailedException.class);
        }
        assertThatThrownBy(() -> JsonValue.of("\uD800")).isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> JsonValue.of("x".repeat(1024 * 1024 + 1)))
                .isInstanceOf(ValidationFailedException.class);
        assertThat(JsonValue.of("😀").json()).isEqualTo("\"😀\"");
    }

    @Test
    void enforcesDepthNodeAndUtf8ByteBudgetsEvenForDirectlyConstructedTrees() {
        Object nested = "x";
        for (int i = 0; i < 34; i++) nested = List.of(nested);
        Object tooDeep = nested;
        assertThatThrownBy(() -> JsonValue.of(tooDeep)).isInstanceOf(ValidationFailedException.class);
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        assertThatThrownBy(() -> JsonValue.of(cycle)).isInstanceOf(ValidationFailedException.class);
        assertThatThrownBy(() -> JsonValue.of(java.util.Collections.nCopies(16385, 1)))
                .isInstanceOf(ValidationFailedException.class);
        JsonValue tree = new JsonValue.ArrayValue(java.util.Collections.nCopies(100, JsonValue.of("é".repeat(6000))));
        assertThatThrownBy(tree::json).isInstanceOf(ValidationFailedException.class);
        JsonValue direct = JsonValue.NullValue.INSTANCE;
        for (int i = 0; i < 34; i++) direct = new JsonValue.ArrayValue(List.of(direct));
        JsonValue tooDeepDirect = direct;
        assertThatThrownBy(tooDeepDirect::json).isInstanceOf(ValidationFailedException.class);
    }

    @Test
    void copiesCollectionsAndDoesNotExposeValuesInDiagnostics() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("secret", "canary");
        var object = (JsonValue.ObjectValue) JsonValue.of(mutable);
        mutable.clear();
        assertThat(object.values()).hasSize(1);
        assertThatThrownBy(() -> object.values().clear()).isInstanceOf(UnsupportedOperationException.class);
        for (JsonValue value : List.of(
                object,
                JsonValue.of("canary"),
                JsonValue.of(9),
                JsonValue.of(true),
                JsonValue.of(List.of("canary")),
                JsonValue.of(null))) {
            assertThat(value.toString()).doesNotContain("canary");
        }
    }
}
