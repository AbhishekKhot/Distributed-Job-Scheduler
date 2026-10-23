package dev.kairos.domain.template;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Bounded JSON conversion and serialization; never introspects arbitrary objects. */
final class JsonValues {
    static final int MAX_NODES = 16384;
    static final int MAX_DEPTH = 32;
    static final int MAX_BYTES = 1024 * 1024;

    private JsonValues() {}

    static JsonValue convert(@Nullable Object value, int depth, int[] nodes) {
        if (depth > MAX_DEPTH || ++nodes[0] > MAX_NODES) throw invalid();
        if (value == null) return JsonValue.NullValue.INSTANCE;
        if (value instanceof String text) return new JsonValue.TextValue(text);
        if (value instanceof Boolean bool) return new JsonValue.BooleanValue(bool);
        if (value instanceof BigDecimal decimal) return new JsonValue.NumberValue(decimal);
        if (value instanceof BigInteger integer) return new JsonValue.NumberValue(new BigDecimal(integer));
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return new JsonValue.NumberValue(BigDecimal.valueOf(((Number) value).longValue()));
        }
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) throw invalid();
            return new JsonValue.NumberValue(new BigDecimal(value.toString()));
        }
        if (value instanceof List<?> list) {
            if (list.size() > MAX_NODES) throw invalid();
            List<JsonValue> converted = new ArrayList<>(list.size());
            for (Object element : list) converted.add(convert(element, depth + 1, nodes));
            return new JsonValue.ArrayValue(converted);
        }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > MAX_NODES) throw invalid();
            Map<String, JsonValue> converted = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name)) throw invalid();
                converted.put(name, convert(entry.getValue(), depth + 1, nodes));
            }
            return new JsonValue.ObjectValue(converted);
        }
        throw invalid();
    }

    static void checkText(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_BYTES || value.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)) throw invalid();
    }

    static String write(JsonValue value) {
        StringBuilder out = new StringBuilder();
        append(value, out, 0, new int[] {0});
        String text = out.toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid();
        return text;
    }

    private static void append(JsonValue value, StringBuilder out, int depth, int[] nodes) {
        if (depth > MAX_DEPTH || ++nodes[0] > MAX_NODES) throw invalid();
        switch (value) {
            case JsonValue.NullValue ignored -> out.append("null");
            case JsonValue.BooleanValue bool -> out.append(bool.value());
            case JsonValue.NumberValue number -> out.append(number.value());
            case JsonValue.TextValue text -> quote(text.value(), out);
            case JsonValue.ArrayValue array -> {
                out.append('[');
                boolean first = true;
                for (JsonValue element : array.values()) {
                    if (!first) out.append(',');
                    first = false;
                    append(element, out, depth + 1, nodes);
                }
                out.append(']');
            }
            case JsonValue.ObjectValue object -> {
                out.append('{');
                boolean first = true;
                for (var entry : new TreeMap<>(object.values()).entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    quote(entry.getKey(), out);
                    out.append(':');
                    append(entry.getValue(), out, depth + 1, nodes);
                }
                out.append('}');
            }
        }
        if (out.length() > MAX_BYTES) throw invalid();
    }

    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) {
                        out.append("\\u00")
                                .append(Character.forDigit(c >>> 4, 16))
                                .append(Character.forDigit(c & 15, 16));
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }

    static ValidationFailedException invalid() {
        return ValidationFailedException.of("body", FieldError.FORMAT);
    }
}
