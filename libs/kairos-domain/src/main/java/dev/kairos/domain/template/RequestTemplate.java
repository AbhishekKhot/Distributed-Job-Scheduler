package dev.kairos.domain.template;

import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Request-value templates, never a template for a URL's host or scheme.
 * @param pathParams endpoint path-segment parameters
 * @param query query parameter values
 * @param headers header values
 * @param body JSON values, or null for no body
 */
public record RequestTemplate(
        Map<String, String> pathParams,
        Map<String, String> query,
        Map<String, String> headers,
        @Nullable JsonValue body) {
    private static final Set<String> MANAGED = Set.of(
            "host",
            "content-length",
            "transfer-encoding",
            "connection",
            "keep-alive",
            "proxy-connection",
            "proxy-authorization",
            "upgrade",
            "te",
            "trailer",
            "expect");
    /** Copies field maps and rejects templates in names or transport-managed headers. */
    public RequestTemplate {
        pathParams = Map.copyOf(pathParams);
        query = Map.copyOf(query);
        headers = Map.copyOf(headers);
        for (Map<String, String> values : List.of(pathParams, query, headers)) {
            if (values.size() > 128) throw invalid();
            values.forEach((name, value) -> {
                if (name.isEmpty()
                        || name.length() > 128
                        || name.contains("{")
                        || name.contains("}")
                        || name.chars().anyMatch(c -> c < 33 || c > 126)) throw invalid();
                Template.compile(value);
            });
        }
        for (String name : headers.keySet()) {
            if (!name.matches("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$") || MANAGED.contains(name.toLowerCase(Locale.ROOT)))
                throw invalid();
        }
        if (body != null) body.json();
    }
    /** Returns an empty request override.
     * @return an empty request override
     */
    public static RequestTemplate empty() {
        return new RequestTemplate(Map.of(), Map.of(), Map.of(), null);
    }

    private static ValidationFailedException invalid() {
        return ValidationFailedException.of("request", FieldError.FORMAT);
    }

    @Override
    public String toString() {
        return "RequestTemplate[<redacted>]";
    }
}
