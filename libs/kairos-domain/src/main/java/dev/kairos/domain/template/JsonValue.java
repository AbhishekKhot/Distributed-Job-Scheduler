package dev.kairos.domain.template;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable framework-free JSON tree. Diagnostics hide contents; serialization is explicit. */
public sealed interface JsonValue {
    /**
     * Converts JSON-compatible Java scalars, lists and string-keyed maps without reflection.
     * @param value a scalar, collection or null
     * @return the immutable tree, with bounded depth and node count
     */
    static JsonValue of(@Nullable Object value) {
        return JsonValues.convert(value, 0, new int[] {0});
    }

    /**
     * Serializes this tree with JSON escaping and a one-MiB UTF-8 bound. Never log the returned text.
     * @return JSON text
     */
    default String json() {
        return JsonValues.write(this);
    }

    /** JSON null. */
    enum NullValue implements JsonValue {
        /** The null singleton. */
        INSTANCE;

        @Override
        public String toString() {
            return "JsonNull";
        }
    }

    /** A JSON boolean.
     * @param value the boolean
     */
    record BooleanValue(boolean value) implements JsonValue {
        @Override
        public String toString() {
            return "JsonBoolean[<redacted>]";
        }
    }

    /** A JSON number.
     * @param value a finite decimal with bounded precision and scale
     */
    record NumberValue(BigDecimal value) implements JsonValue {
        /** Validates the decimal without converting it to floating point. */
        public NumberValue {
            Objects.requireNonNull(value, "value");
            if (value.precision() > 8192 || Math.abs((long) value.scale()) > 8192) throw JsonValues.invalid();
        }

        @Override
        public String toString() {
            return "JsonNumber[<redacted>]";
        }
    }

    /** A JSON string.
     * @param value a bounded Unicode string
     */
    record TextValue(String value) implements JsonValue {
        /** Validates length and Unicode scalar values. */
        public TextValue {
            JsonValues.checkText(value);
        }

        @Override
        public String toString() {
            return "JsonText[<redacted>]";
        }
    }

    /** A JSON array.
     * @param values the array elements, copied
     */
    record ArrayValue(List<JsonValue> values) implements JsonValue {
        /** Copies elements; nested values are immutable. */
        public ArrayValue {
            if (values.size() > JsonValues.MAX_NODES) throw JsonValues.invalid();
            values = List.copyOf(values);
        }

        @Override
        public String toString() {
            return "JsonArray[<redacted>]";
        }
    }

    /** A JSON object.
     * @param values object properties, copied
     */
    record ObjectValue(Map<String, JsonValue> values) implements JsonValue {
        /** Copies properties and validates their names as JSON strings. */
        public ObjectValue {
            if (values.size() > JsonValues.MAX_NODES) throw JsonValues.invalid();
            values.keySet().forEach(JsonValues::checkText);
            values = Map.copyOf(values);
        }

        @Override
        public String toString() {
            return "JsonObject[<redacted>]";
        }
    }
}
