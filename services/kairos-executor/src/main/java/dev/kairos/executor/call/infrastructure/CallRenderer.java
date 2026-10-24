package dev.kairos.executor.call.infrastructure;

import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.template.JsonValue;
import dev.kairos.domain.template.OutputValue;
import dev.kairos.domain.template.RequestTemplate;
import dev.kairos.domain.template.TemplateEngine;
import dev.kairos.http.auth.AuthMaterial;
import dev.kairos.http.auth.Authenticator;
import dev.kairos.http.client.CaBundle;
import dev.kairos.http.client.HttpClientFactory;
import dev.kairos.http.request.HeaderField;
import dev.kairos.http.request.OutboundRequest;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.NameValue;
import dev.kairos.security.keyset.TeamSigningKey;
import dev.kairos.security.signing.RequestSigner;
import dev.kairos.security.signing.SignatureHeaders;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** Renders dispatch snapshots without double-encoding the path and query components from T-104. */
public final class CallRenderer {

    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_-]{0,63})}");

    private final JdbcExecutionSecrets secrets;
    private final HttpClientFactory clients;
    private final RequestSigner signer;
    private final JsonMapper json;
    private final Authenticator authenticator;

    /** Creates the call renderer. */
    public CallRenderer(
            JdbcExecutionSecrets secrets,
            HttpClientFactory clients,
            RequestSigner signer,
            JsonMapper json,
            Authenticator authenticator) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.json = Objects.requireNonNull(json, "json");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    /** Renders endpoint URL, workflow templates, auth, standard headers and request signature. */
    public RenderedCall render(Dispatch dispatch, EndpointVersion endpoint) {
        TeamId team = new TeamId(uuid(dispatch.getTeamId()));
        EndpointId endpointId = new EndpointId(uuid(dispatch.getEndpointId()));
        RunId runId = new RunId(uuid(dispatch.getRunId()));
        RunId workflowRunId = new RunId(uuid(dispatch.getTargetId()));
        Map<String, String> path = new LinkedHashMap<>(dispatch.getRequest().getPathParamsMap());
        Map<String, String> query = new LinkedHashMap<>();
        for (NameValue value : dispatch.getRequest().getQueryList()) query.put(value.getName(), value.getValue());
        Map<String, String> headers = new LinkedHashMap<>();
        endpoint.defaultHeaders().forEach((name, value) -> headers.put(name, value));
        for (NameValue value : dispatch.getRequest().getHeadersList()) {
            putHeader(headers, value.getName(), value.getValue());
        }

        JsonValue body = null;
        byte[] snapshotBody = dispatch.getRequest().getBody().toByteArray();
        if (snapshotBody.length > 0) {
            try {
                body = JsonValue.of(json.readValue(snapshotBody, Object.class));
            } catch (Exception invalid) {
                throw new IllegalArgumentException("The request body could not be rendered", invalid);
            }
        }
        RequestTemplate template = new RequestTemplate(path, query, headers, body);
        Map<TaskKey, Map<String, OutputValue>> inputs = secrets.inputs(
                team, workflowRunId, dispatch.getRequest().getSealedInputs().toByteArray());
        TemplateEngine.RenderedRequest rendered =
                TemplateEngine.renderRequest(template, new TemplateEngine.Context(runId.value(), inputs));
        URI url = renderUrl(endpoint.urlTemplate(), rendered.pathParams(), rendered.query());
        byte[] renderedBody =
                rendered.body() == null ? new byte[0] : rendered.body().json().getBytes(StandardCharsets.UTF_8);
        OutboundRequest request = OutboundRequest.builder(endpoint.method(), url, endpoint.timeout())
                .body(renderedBody)
                .build();
        for (Map.Entry<String, String> header : rendered.headers().entrySet()) {
            request = request.withHeader(new HeaderField(header.getKey(), header.getValue()));
        }
        if (renderedBody.length > 0 && request.header("Content-Type").isEmpty()) {
            request = request.withHeader(new HeaderField("Content-Type", "application/json"));
        }
        request = request.withHeader(new HeaderField("Idempotency-Key", runId.toString()))
                .withHeader(new HeaderField("X-Kairos-Run-Id", runId.toString()))
                .withHeader(new HeaderField("X-Kairos-Attempt", Integer.toString(dispatch.getAttempt())));
        if (!dispatch.getTraceParent().isBlank()) {
            request = request.withHeader(new HeaderField("traceparent", dispatch.getTraceParent()));
        }

        AuthMaterial auth = secrets.auth(team, endpointId, dispatch.getEndpointVersion(), endpoint);
        CaBundle bundle = endpoint.caBundle() == null ? null : secrets.caBundle(team, endpoint.caBundle());
        var client = bundle == null ? clients.defaultClient() : clients.forCaBundle(bundle);
        request = authenticator.apply(request, auth, client);
        TeamSigningKey signingKey = secrets.signingKey(team);
        SignatureHeaders signature =
                signer.sign(signingKey, request.method().name(), pathAndQuery(request.url()), runId, request.body());
        request = request.withHeader(new HeaderField(RequestSigner.TIMESTAMP_HEADER, signature.timestamp()))
                .withHeader(new HeaderField(RequestSigner.SIGNATURE_HEADER, signature.signature()));
        List<String> redactionInputs = new ArrayList<>(auth.secrets());
        inputs.values()
                .forEach(values -> values.values().stream()
                        .filter(OutputValue::sensitive)
                        .map(OutputValue::value)
                        .map(CallRenderer::plainText)
                        .forEach(redactionInputs::add));
        return new RenderedCall(request, auth, client, redactionInputs);
    }

    private static URI renderUrl(String urlTemplate, Map<String, String> path, Map<String, String> query) {
        StringBuilder rendered = new StringBuilder(urlTemplate.length() + 128);
        Matcher matcher = PATH_PARAMETER.matcher(urlTemplate);
        int previous = 0;
        while (matcher.find()) {
            int schemeEnd = urlTemplate.indexOf("://") + 3;
            int pathStart = urlTemplate.indexOf('/', schemeEnd);
            int queryStart = urlTemplate.indexOf('?', schemeEnd);
            if (pathStart < 0) pathStart = queryStart < 0 ? urlTemplate.length() : queryStart;
            if (matcher.start() < pathStart || (queryStart >= 0 && matcher.start() > queryStart)) {
                throw new IllegalArgumentException("URL placeholders are allowed only in the path");
            }
            String value = path.get(matcher.group(1));
            if (value == null) throw new IllegalArgumentException("A required path parameter is missing");
            rendered.append(urlTemplate, previous, matcher.start()).append(value);
            previous = matcher.end();
        }
        rendered.append(urlTemplate, previous, urlTemplate.length());
        StringBuilder complete = new StringBuilder(rendered);
        for (Map.Entry<String, String> entry : query.entrySet()) {
            complete.append(complete.indexOf("?") < 0 ? '?' : '&')
                    .append(encode(entry.getKey()))
                    .append('=')
                    .append(entry.getValue());
        }
        return URI.create(complete.toString());
    }

    private static String pathAndQuery(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private static String encode(String raw) {
        StringBuilder out = new StringBuilder();
        for (byte value : raw.getBytes(StandardCharsets.UTF_8)) {
            int c = value & 0xff;
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.'
                    || c == '~') {
                out.append((char) c);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit(c >>> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return out.toString();
    }

    private static String plainText(JsonValue value) {
        return switch (value) {
            case JsonValue.TextValue text -> text.value();
            case JsonValue.NumberValue number -> number.value().toPlainString();
            case JsonValue.BooleanValue bool -> Boolean.toString(bool.value());
            case JsonValue.NullValue ignored -> "null";
            case JsonValue.ArrayValue array ->
                array.values().stream().map(CallRenderer::plainText).collect(java.util.stream.Collectors.joining(","));
            case JsonValue.ObjectValue object ->
                object.values().values().stream()
                        .map(CallRenderer::plainText)
                        .collect(java.util.stream.Collectors.joining(","));
        };
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        String existing = headers.keySet().stream()
                .filter(key -> key.equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
        if (existing != null) headers.remove(existing);
        headers.put(name, value);
    }

    private static UUID uuid(com.google.protobuf.ByteString bytes) {
        return dev.kairos.executor.consume.application.DispatchIds.uuid(bytes);
    }
}
