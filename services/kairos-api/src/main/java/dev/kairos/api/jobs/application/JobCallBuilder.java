package dev.kairos.api.jobs.application;

import dev.kairos.api.endpoints.application.EndpointSpec;
import dev.kairos.proto.v1.NameValue;
import dev.kairos.proto.v1.RequestSnapshot;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** Builds the dispatch snapshot shared by immediate and manually triggered job runs. */
public final class JobCallBuilder {
    private static final Set<String> CREDENTIAL_HEADERS =
            Set.of("authorization", "proxy-authorization", "cookie", "set-cookie");

    private JobCallBuilder() {}

    /** Builds a call snapshot without resolving or copying any credential value. */
    public static dev.kairos.engine.run.CallSpec build(
            EndpointSpec endpoint, JobSpec.Request request, JsonMapper json) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(request, "request");
        RequestSnapshot.Builder snapshot = RequestSnapshot.newBuilder();
        request.pathParams().forEach(snapshot::putPathParams);
        request.query()
                .forEach((name, value) -> snapshot.addQuery(
                        NameValue.newBuilder().setName(name).setValue(value).build()));
        Map<String, NameValue> headers = new LinkedHashMap<>();
        endpoint.defaultHeaders().forEach((name, value) -> putHeader(headers, name, value));
        request.headers().forEach((name, value) -> putHeader(headers, name, value));
        snapshot.addAllHeaders(headers.values());
        if (request.body() != null)
            snapshot.setBody(com.google.protobuf.ByteString.copyFrom(json.writeValueAsBytes(request.body())));
        if (endpoint.auth().secretRef() != null)
            snapshot.addSecretRefs(endpoint.auth().secretRef());
        return new dev.kairos.engine.run.CallSpec(endpoint.timeoutMs(), snapshot.build());
    }

    private static void putHeader(Map<String, NameValue> headers, String name, String value) {
        if (CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT))) return;
        headers.put(
                name.toLowerCase(Locale.ROOT),
                NameValue.newBuilder().setName(name).setValue(value).build());
    }
}
