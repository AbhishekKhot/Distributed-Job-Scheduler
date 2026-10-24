package dev.kairos.executor.call.infrastructure;

import dev.kairos.domain.retry.StatusRules;
import dev.kairos.domain.template.OutputSpec;
import dev.kairos.http.auth.ApiKeyLocation;
import dev.kairos.http.auth.AuthType;
import dev.kairos.http.request.HttpMethod;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Immutable, pinned endpoint settings required to render and execute one call. */
public record EndpointVersion(
        String urlTemplate,
        HttpMethod method,
        Duration timeout,
        Map<String, String> defaultHeaders,
        AuthType authType,
        @Nullable String secretRef,
        @Nullable ApiKeyLocation apiKeyLocation,
        @Nullable String apiKeyName,
        @Nullable URI tokenUrl,
        @Nullable String oauthScope,
        @Nullable String caBundle,
        StatusRules statusRules,
        Map<String, OutputSpec> outputs,
        int ratePerSec) {

    /** Validates and copies the endpoint contract. */
    public EndpointVersion {
        Objects.requireNonNull(urlTemplate, "urlTemplate");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(timeout, "timeout");
        defaultHeaders = Map.copyOf(defaultHeaders);
        Objects.requireNonNull(authType, "authType");
        Objects.requireNonNull(statusRules, "statusRules");
        outputs = Map.copyOf(outputs);
        if (timeout.isNegative() || timeout.isZero() || ratePerSec < 0) {
            throw new IllegalArgumentException("Endpoint limits are invalid");
        }
        if (authType == AuthType.API_KEY && (apiKeyLocation == null || apiKeyName == null)) {
            throw new IllegalArgumentException("API key settings are missing");
        }
        if (authType == AuthType.OAUTH2_CLIENT_CREDENTIALS && tokenUrl == null) {
            throw new IllegalArgumentException("OAuth token URL is missing");
        }
    }

    @Override
    public String toString() {
        return "EndpointVersion[method=" + method + ", authType=" + authType + ", outputs=" + outputs.size() + "]";
    }
}
