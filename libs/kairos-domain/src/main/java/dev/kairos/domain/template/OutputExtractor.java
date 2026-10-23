package dev.kairos.domain.template;

import dev.kairos.domain.model.ErrorClass;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Bounded, framework-free extraction. Optional missing values are omitted, while explicit JSON null is retained. */
public final class OutputExtractor {
    /** Total serialized output-object bytes per task, including names and sensitive values. */
    public static final int MAX_OUTPUT_BYTES = 24 * 1024;

    private OutputExtractor() {}

    /**
     * Extracts values from a decoded response. HEADER requires one unambiguous field value.
     * @param specs validated output declarations
     * @param body decoded JSON, or null when absent or not JSON
     * @param headers response headers, matched without case sensitivity
     * @param status HTTP status, 100 to 599
     * @return immutable values with sensitivity labels
     * @throws OutputExtractionException on missing required data, ambiguous headers or excess size
     */
    public static Map<String, OutputValue> extract(
            Map<String, OutputSpec> specs, @Nullable JsonValue body, Map<String, List<String>> headers, int status) {
        if (specs.size() > 256 || status < 100 || status > 599) throw missing();
        Map<String, OutputValue> result = new LinkedHashMap<>();
        int bytes = 2;
        for (var entry : specs.entrySet()) {
            if (!Template.name(entry.getKey())) throw missing();
            OutputSpec spec = entry.getValue();
            JsonValue value = switch (spec.from()) {
                case BODY -> OutputPath.extract(body, OutputPath.parse(spec.path()));
                case HEADER -> header(headers, spec.path());
                case STATUS -> new JsonValue.NumberValue(BigDecimal.valueOf(status));
            };
            if (value == null) {
                if (spec.required()) throw missing();
                continue;
            }
            result.put(entry.getKey(), new OutputValue(value, spec.sensitive()));
            bytes += serializedSize(entry.getKey(), value) + (result.size() == 1 ? 0 : 1);
            if (bytes > MAX_OUTPUT_BYTES) throw new OutputExtractionException(ErrorClass.RESPONSE_TOO_LARGE);
        }
        return Map.copyOf(result);
    }

    static int serializedSize(String name, JsonValue value) {
        try {
            return new JsonValue.TextValue(name).json().getBytes(StandardCharsets.UTF_8).length
                    + 1
                    + value.json().getBytes(StandardCharsets.UTF_8).length;
        } catch (dev.kairos.domain.error.ValidationFailedException failure) {
            throw new OutputExtractionException(ErrorClass.RESPONSE_TOO_LARGE);
        }
    }

    private static @Nullable JsonValue header(Map<String, List<String>> headers, String name) {
        String value = null;
        for (var entry : headers.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase(name)) continue;
            for (String candidate : entry.getValue()) {
                if (value != null) throw missing();
                value = candidate;
            }
        }
        return value == null ? null : new JsonValue.TextValue(value);
    }

    private static OutputExtractionException missing() {
        return new OutputExtractionException(ErrorClass.OUTPUT_MISSING);
    }
}
