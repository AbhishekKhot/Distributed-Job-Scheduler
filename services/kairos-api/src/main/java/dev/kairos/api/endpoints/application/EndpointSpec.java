package dev.kairos.api.endpoints.application;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.template.OutputSpec;
import dev.kairos.http.policy.TargetDecision;
import dev.kairos.http.policy.TargetPolicy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Validated endpoint configuration stored as an immutable version. */
public record EndpointSpec(
        String method,
        String urlTemplate,
        Map<String, String> defaultHeaders,
        Auth auth,
        @Nullable String caBundle,
        int timeoutMs,
        Set<String> successStatus,
        Map<String, String> statusOverrides,
        RetryPolicy retryPolicy,
        Limits limits,
        Map<String, Output> outputs,
        UnknownOutcomePolicy unknownOutcomePolicy,
        SuspendPolicy suspendPolicy) {

    private static final Pattern PARAMETER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]{0,63})}");
    private static final Set<String> MANAGED_HEADERS = Set.of(
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
            "expect",
            "authorization");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");

    public EndpointSpec {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(urlTemplate, "urlTemplate");
        Objects.requireNonNull(defaultHeaders, "defaultHeaders");
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(successStatus, "successStatus");
        Objects.requireNonNull(statusOverrides, "statusOverrides");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(outputs, "outputs");
        Objects.requireNonNull(unknownOutcomePolicy, "unknownOutcomePolicy");
        Objects.requireNonNull(suspendPolicy, "suspendPolicy");
        method = method.toUpperCase(java.util.Locale.ROOT);
        defaultHeaders = Map.copyOf(defaultHeaders);
        successStatus = Set.copyOf(successStatus);
        statusOverrides = Map.copyOf(statusOverrides);
        outputs = Map.copyOf(outputs);
        validate(
                method,
                urlTemplate,
                defaultHeaders,
                auth,
                caBundle,
                timeoutMs,
                successStatus,
                statusOverrides,
                retryPolicy,
                limits,
                outputs);
    }

    /** Checks the URL template against the platform target policy and structural rules. */
    public void validateTarget(TargetPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        StringBuilder rendered = new StringBuilder();
        Matcher matcher = PARAMETER.matcher(urlTemplate);
        int previous = 0;
        while (matcher.find()) {
            String preceding = urlTemplate.substring(previous, matcher.start());
            if (preceding.indexOf('?') >= 0 || preceding.indexOf('#') >= 0) {
                invalid("urlTemplate", "path-parameters-only");
            }
            rendered.append(preceding).append("x");
            previous = matcher.end();
        }
        rendered.append(urlTemplate.substring(previous));
        String withoutParameters = PARAMETER.matcher(urlTemplate).replaceAll("");
        if (withoutParameters.indexOf('{') >= 0 || withoutParameters.indexOf('}') >= 0) {
            invalid("urlTemplate", "format");
        }
        try {
            URI uri = URI.create(rendered.toString());
            if (!"https".equalsIgnoreCase(uri.getScheme())) invalid("urlTemplate", "https-required");
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                if (PARAMETER.matcher(urlTemplate).find()) invalid("urlTemplate", "path-parameters-only");
            }
            if (policy.checkUrl(uri) instanceof TargetDecision.Denied) {
                invalid("urlTemplate", "target-not-allowed");
            }
            if (auth.tokenUrl() != null) {
                URI tokenUri = URI.create(auth.tokenUrl());
                if (policy.checkUrl(tokenUri) instanceof TargetDecision.Denied) {
                    invalid("auth.tokenUrl", "target-not-allowed");
                }
            }
        } catch (IllegalArgumentException exception) {
            invalid("urlTemplate", "https-required");
        }
    }

    private static void validate(
            String method,
            String url,
            Map<String, String> headers,
            Auth auth,
            @Nullable String caBundle,
            int timeout,
            Set<String> success,
            Map<String, String> overrides,
            RetryPolicy retry,
            Limits limits,
            Map<String, Output> outputs) {
        if (!METHODS.contains(method)) invalid("method", "enum");
        if (url.isBlank() || url.getBytes(StandardCharsets.UTF_8).length > 2048)
            invalid("urlTemplate", FieldError.FORMAT);
        if (timeout < 1000 || timeout > 300000) invalid("timeoutMs", "range");
        if (success.isEmpty()
                || success.size() > 10
                || success.stream().anyMatch(s -> !s.matches("(?:[1-5]xx|[1-5][0-9]{2})"))) {
            invalid("successStatus", "format");
        }
        if (overrides.size() > 50
                || overrides.entrySet().stream()
                        .anyMatch(e -> !e.getKey().matches("[1-5][0-9]{2}")
                                || !Set.of("SUCCESS", "RETRY", "FAIL").contains(e.getValue()))) {
            invalid("statusOverrides", "format");
        }
        for (var header : headers.entrySet()) {
            if (header.getKey().isBlank()
                    || header.getKey()
                            .chars()
                            .anyMatch(c -> c <= 32 || c >= 127 || "()<>@,;:\\\"/[]?={}".indexOf(c) >= 0)
                    || MANAGED_HEADERS.contains(header.getKey().toLowerCase(java.util.Locale.ROOT))) {
                invalid("defaultHeaders", "managed-header");
            }
            if (header.getValue() == null
                    || header.getValue().indexOf('\r') >= 0
                    || header.getValue().indexOf('\n') >= 0) {
                invalid("defaultHeaders", "invalid-value");
            }
        }
        if (caBundle != null && !caBundle.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) invalid("caBundle", "format");
        if (auth.type != AuthType.NONE
                && (auth.secretRef == null || !auth.secretRef.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
            invalid("auth.secretRef", "required");
        }
        if (auth.type == AuthType.NONE && auth.secretRef != null) invalid("auth.secretRef", "unexpected");
        if (auth.type == AuthType.API_KEY) {
            if (auth.location == null || !Set.of("HEADER", "QUERY").contains(auth.location)) {
                invalid("auth.location", "enum");
            }
            if (auth.name == null || !auth.name.matches("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}$")) {
                invalid("auth.name", "required");
            }
        } else if (auth.location != null || auth.name != null) {
            invalid("auth", "unexpected");
        }
        if (auth.type == AuthType.OAUTH2_CLIENT_CREDENTIALS) {
            if (auth.tokenUrl == null || auth.tokenUrl.isBlank()) invalid("auth.tokenUrl", "required");
        } else if (auth.tokenUrl != null || auth.scope != null) {
            invalid("auth", "unexpected");
        }
        if (outputs.size() > 256) invalid("outputs", "size");
        outputs.forEach((name, output) -> {
            if (!name.matches("^[A-Za-z][A-Za-z0-9_-]{0,63}$")) invalid("outputs." + name, "pattern");
            new OutputSpec(
                    OutputSpec.Source.valueOf(output.from().name()),
                    output.path() == null ? "" : output.path(),
                    output.required(),
                    output.sensitive());
        });
    }

    private static void invalid(String field, String code) {
        if ("target-not-allowed".equals(code)) {
            throw new ValidationFailedException(
                    ErrorCode.TARGET_NOT_ALLOWED,
                    "The host is not an internal address allowed by the platform target policy.",
                    java.util.List.of());
        }
        throw ValidationFailedException.of(field, code);
    }

    public enum AuthType {
        NONE,
        BASIC,
        BEARER,
        API_KEY,
        OAUTH2_CLIENT_CREDENTIALS
    }

    public enum UnknownOutcomePolicy {
        RETRY_WITH_IDEMPOTENCY_KEY,
        REVIEW
    }

    public enum RetryStrategy {
        EXPONENTIAL_JITTER,
        FULL_JITTER,
        FIXED
    }

    public enum OutputSource {
        BODY,
        HEADER,
        STATUS
    }

    public record Auth(
            AuthType type,
            @Nullable String secretRef,
            @Nullable String location,
            @Nullable String name,
            @Nullable String tokenUrl,
            @Nullable String scope) {
        public Auth {
            Objects.requireNonNull(type, "type");
        }
    }

    public record RetryPolicy(
            int maxRetries, RetryStrategy strategy, int baseDelaySec, int maxDelaySec, int maxAgeSec) {
        public RetryPolicy {
            Objects.requireNonNull(strategy, "strategy");
            if (maxRetries < 0
                    || maxRetries > 20
                    || baseDelaySec < 1
                    || baseDelaySec > 3600
                    || maxDelaySec < baseDelaySec
                    || maxDelaySec > 86400
                    || maxAgeSec < 60
                    || maxAgeSec > 604800) {
                invalid("retryPolicy", "range");
            }
        }
    }

    public record Limits(int maxConcurrency, int ratePerSec) {
        public Limits {
            if (maxConcurrency < 1 || maxConcurrency > 10000 || ratePerSec < 0 || ratePerSec > 100000)
                invalid("limits", "range");
        }
    }

    public record Output(OutputSource from, @Nullable String path, boolean required, boolean sensitive) {
        public Output {
            Objects.requireNonNull(from, "from");
        }
    }

    public record SuspendPolicy(boolean enabled) {}
}
