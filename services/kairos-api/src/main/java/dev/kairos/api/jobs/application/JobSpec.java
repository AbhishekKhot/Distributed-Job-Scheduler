package dev.kairos.api.jobs.application;

import dev.kairos.api.endpoints.application.EndpointSpec;
import dev.kairos.api.endpoints.application.EndpointSpec.RetryPolicy;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.model.MisfirePolicy;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.schedule.ScheduleRequest;
import dev.kairos.domain.schedule.ScheduleSpec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validated, immutable job definition stored in {@code jobs.spec} and {@code job_versions.spec}. */
public record JobSpec(
        String name,
        ScheduleSpec schedule,
        int spreadSec,
        String endpoint,
        Request request,
        int priority,
        @Nullable RetryPolicy retryPolicy,
        MisfirePolicy misfirePolicy,
        int maxCatchup,
        @Nullable Integer maxLatenessSec,
        int autoPauseAfter,
        JsonNode serialized) {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern PARAMETER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_-]{0,63})}");
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
            "authorization",
            "cookie",
            "set-cookie");
    public static final int MAX_SPEC_BYTES = 128 * 1024;

    public JobSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(misfirePolicy, "misfirePolicy");
        Objects.requireNonNull(serialized, "serialized");
        if (!NAME.matcher(name).matches()) invalid("name", FieldError.PATTERN);
        if (!endpoint.matches("[a-z][a-z0-9-]{1,62}")) invalid("endpoint", FieldError.PATTERN);
        Priority.of(priority);
        if (spreadSec < 0 || spreadSec > 60) invalid("schedule.spreadSec", FieldError.RANGE);
        if (maxCatchup < 0 || maxCatchup > 1000) invalid("maxCatchup", FieldError.RANGE);
        if (maxLatenessSec != null && (maxLatenessSec < 0 || (long) maxLatenessSec * 1000 > Integer.MAX_VALUE)) {
            invalid("maxLatenessSec", FieldError.RANGE);
        }
        if (autoPauseAfter < 0 || autoPauseAfter > 100) invalid("autoPauseAfter", FieldError.RANGE);
    }

    /** Parses and validates one job body and caps the immutable JSON definition at 128 KiB. */
    public static JobSpec parse(JsonNode body, Instant now, JsonMapper json) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(json, "json");
        if (!body.isObject()) invalid("", FieldError.FORMAT);
        String name = text(body, "name", "name");
        JsonNode scheduleNode = body.path("schedule");
        if (!scheduleNode.isObject()) invalid("schedule", FieldError.REQUIRED);
        String type = nullableText(scheduleNode, "type");
        ScheduleRequest scheduleRequest = new ScheduleRequest(
                type,
                nullableText(scheduleNode, "at"),
                nullableText(scheduleNode, "expression"),
                nullableText(scheduleNode, "timezone"),
                nullableLong(scheduleNode, "everySec"));
        ScheduleSpec schedule = scheduleRequest.toSpec(now);
        int spreadSec = integer(scheduleNode, "spreadSec", 0, "schedule.spreadSec");
        String endpoint = text(body, "endpoint", "endpoint");
        Request request = parseRequest(body.path("request"));
        int priority = integer(body, "priority", 5, "priority");
        EndpointSpec.RetryPolicy retry = parseRetry(body.path("retryPolicy"));
        MisfirePolicy misfire = enumValue(body, "misfirePolicy", MisfirePolicy.class, MisfirePolicy.DEFAULT);
        int maxCatchup = integer(body, "maxCatchup", 100, "maxCatchup");
        Integer maxLateness = nullableInteger(body, "maxLatenessSec", "maxLatenessSec");
        int autoPauseAfter = integer(body, "autoPauseAfter", 3, "autoPauseAfter");
        JsonNode saved = body.deepCopy();
        if (json.writeValueAsBytes(saved).length > MAX_SPEC_BYTES) invalid("", "too-large");
        return new JobSpec(
                name,
                schedule,
                spreadSec,
                endpoint,
                request,
                priority,
                retry,
                misfire,
                maxCatchup,
                maxLateness,
                autoPauseAfter,
                saved);
    }

    /** Validates that the request has exactly the endpoint's path parameters and safe HTTP headers. */
    public void validateEndpoint(String urlTemplate, Map<String, String> defaultHeaders) {
        validateEndpoint(request, urlTemplate, defaultHeaders);
    }

    /** Validates an effective request after applying a per-run override. */
    public static void validateEndpoint(Request request, String urlTemplate, Map<String, String> defaultHeaders) {
        Matcher matcher = PARAMETER.matcher(urlTemplate);
        java.util.HashSet<String> required = new java.util.HashSet<>();
        while (matcher.find()) required.add(matcher.group(1));
        if (!request.pathParams().keySet().containsAll(required)) invalid("request.pathParams", "required");
        if (!required.containsAll(request.pathParams().keySet())) invalid("request.pathParams", "unexpected");
        java.util.HashSet<String> seenHeaders = new java.util.HashSet<>();
        request.headers().forEach((key, value) -> {
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (MANAGED_HEADERS.contains(lower)) invalid("request.headers." + key, "managed-header");
            if (key.isBlank()
                    || key.length() > 128
                    || key.chars().anyMatch(c -> c <= 32 || c >= 127 || "()<>@,;:\\\"/[]?={}".indexOf(c) >= 0)) {
                invalid("request.headers." + key, "format");
            }
            if (!seenHeaders.add(lower)) invalid("request.headers." + key, "duplicate");
            if (value.length() > 8192 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                invalid("request.headers." + key, "format");
            }
        });
        request.query().forEach((key, value) -> {
            if (key.isBlank() || key.length() > 128 || value.length() > 8192) invalid("request.query." + key, "range");
        });
        request.pathParams().forEach((key, value) -> {
            if (value.length() > 8192) invalid("request.pathParams." + key, "range");
        });
    }

    private static Request parseRequest(JsonNode node) {
        if (!node.isObject()) invalid("request", FieldError.REQUIRED);
        return request(node);
    }

    /** Parses just the request-values object used by a manual run override. */
    public static Request request(JsonNode node) {
        if (!node.isObject()) invalid("request", FieldError.FORMAT);
        return new Request(
                stringMap(node.path("pathParams"), "request.pathParams"),
                stringMap(node.path("query"), "request.query"),
                stringMap(node.path("headers"), "request.headers"),
                node.has("body") ? node.get("body").deepCopy() : null);
    }

    private static Map<String, String> stringMap(JsonNode node, String field) {
        if (node.isMissingNode() || node.isNull()) return Map.of();
        if (!node.isObject()) invalid(field, FieldError.FORMAT);
        Map<String, String> values = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            if (!entry.getValue().isString()) invalid(field + "." + entry.getKey(), FieldError.FORMAT);
            values.put(entry.getKey(), entry.getValue().asString());
        });
        return Map.copyOf(values);
    }

    private static @Nullable RetryPolicy parseRetry(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        if (!node.isObject()) invalid("retryPolicy", FieldError.FORMAT);
        try {
            return new RetryPolicy(
                    integer(node, "maxRetries", 5, "retryPolicy.maxRetries"),
                    enumValue(
                            node,
                            "strategy",
                            EndpointSpec.RetryStrategy.class,
                            EndpointSpec.RetryStrategy.EXPONENTIAL_JITTER),
                    integer(node, "baseDelaySec", 30, "retryPolicy.baseDelaySec"),
                    integer(node, "maxDelaySec", 600, "retryPolicy.maxDelaySec"),
                    integer(node, "maxAgeSec", 86400, "retryPolicy.maxAgeSec"));
        } catch (IllegalArgumentException exception) {
            invalid("retryPolicy", FieldError.RANGE);
            throw exception;
        }
    }

    private static String text(JsonNode node, String key, String field) {
        JsonNode value = node.path(key);
        if (!value.isString() || value.asString().isBlank()) invalid(field, FieldError.REQUIRED);
        return value.asString();
    }

    private static @Nullable String nullableText(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isString()) invalid("schedule." + key, FieldError.FORMAT);
        return value.asString();
    }

    private static int integer(JsonNode node, String key, int fallback, String field) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return fallback;
        if (!value.canConvertToInt()) invalid(field, FieldError.FORMAT);
        return value.intValue();
    }

    private static @Nullable Long nullableLong(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.canConvertToLong()) invalid("schedule." + key, FieldError.FORMAT);
        return value.longValue();
    }

    private static @Nullable Integer nullableInteger(JsonNode node, String key, String field) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.canConvertToInt()) invalid(field, FieldError.FORMAT);
        return value.intValue();
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, String key, Class<E> type, E fallback) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return fallback;
        if (!value.isString()) invalid(key, FieldError.FORMAT);
        try {
            return Enum.valueOf(type, value.asString());
        } catch (IllegalArgumentException exception) {
            invalid(key, "enum");
            throw exception;
        }
    }

    private static void invalid(String field, String code) {
        throw ValidationFailedException.of(field, code);
    }

    /** Request data applied to a job run. */
    public record Request(
            Map<String, String> pathParams,
            Map<String, String> query,
            Map<String, String> headers,
            @Nullable JsonNode body) {
        public Request {
            pathParams = Map.copyOf(pathParams);
            query = Map.copyOf(query);
            headers = Map.copyOf(headers);
            body = body == null ? null : body.deepCopy();
        }
    }
}
