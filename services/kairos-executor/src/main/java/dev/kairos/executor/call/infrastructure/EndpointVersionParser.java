package dev.kairos.executor.call.infrastructure;

import dev.kairos.domain.retry.StatusOverride;
import dev.kairos.domain.retry.StatusRules;
import dev.kairos.domain.template.OutputSpec;
import dev.kairos.http.auth.ApiKeyLocation;
import dev.kairos.http.auth.AuthType;
import dev.kairos.http.request.HttpMethod;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Parses the immutable JSON endpoint version using the LLD 3.5 schema. */
public final class EndpointVersionParser {

    /** Parses and validates an endpoint version. */
    public EndpointVersion parse(JsonNode spec) {
        Objects.requireNonNull(spec, "spec");
        HttpMethod method = HttpMethod.valueOf(text(spec, "method"));
        String url = text(spec, "urlTemplate");
        Duration timeout = Duration.ofMillis(integer(spec, "timeoutMs", 30_000));
        Map<String, String> headers = stringMap(spec.path("defaultHeaders"));
        JsonNode auth = spec.path("auth");
        AuthType authType = AuthType.valueOf(text(auth, "type"));
        String secretRef = nullableText(auth.path("secretRef"));
        ApiKeyLocation keyLocation = nullableEnum(ApiKeyLocation.class, auth.path("location"));
        String keyName = nullableText(auth.path("name"));
        String tokenUrlValue = nullableText(auth.path("tokenUrl"));
        URI tokenUrl = tokenUrlValue == null ? null : URI.create(tokenUrlValue);
        String scope = nullableText(auth.path("scope"));
        String caBundle = nullableText(spec.path("caBundle"));

        JsonNode success = spec.path("successStatus");
        List<String> successStatuses = success.isArray()
                ? success.valueStream().map(JsonNode::asString).toList()
                : List.of("2xx");
        Map<String, StatusOverride> overrides = new LinkedHashMap<>();
        JsonNode overrideNode = spec.path("statusOverrides");
        if (overrideNode.isObject()) {
            overrideNode
                    .properties()
                    .forEach(entry -> overrides.put(
                            entry.getKey(),
                            StatusOverride.valueOf(entry.getValue().asString())));
        }

        Map<String, OutputSpec> outputs = new LinkedHashMap<>();
        JsonNode outputNode = spec.path("outputs");
        if (outputNode.isObject()) {
            outputNode.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                outputs.put(
                        entry.getKey(),
                        new OutputSpec(
                                OutputSpec.Source.valueOf(text(value, "from")),
                                nullableText(value.path("path")) == null ? "" : text(value, "path"),
                                bool(value, "required", false),
                                bool(value, "sensitive", false)));
            });
        }
        int ratePerSec = integer(spec.path("limits"), "ratePerSec", 0);
        return new EndpointVersion(
                url,
                method,
                timeout,
                headers,
                authType,
                secretRef,
                keyLocation,
                keyName,
                tokenUrl,
                scope,
                caBundle,
                StatusRules.parse(successStatuses, overrides),
                outputs,
                ratePerSec);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) throw new IllegalArgumentException("Pinned endpoint configuration is invalid");
        return value.asString();
    }

    private static @Nullable String nullableText(JsonNode value) {
        return value.isString() ? value.asString() : null;
    }

    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        return value.canConvertToInt() ? value.intValue() : fallback;
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.path(field);
        return value.isBoolean() ? value.booleanValue() : fallback;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (!entry.getValue().isString())
                    throw new IllegalArgumentException("Pinned endpoint configuration is invalid");
                values.put(entry.getKey(), entry.getValue().asString());
            });
        }
        return Map.copyOf(values);
    }

    private static <T extends Enum<T>> @Nullable T nullableEnum(Class<T> type, JsonNode value) {
        return value.isString() ? Enum.valueOf(type, value.asString()) : null;
    }
}
