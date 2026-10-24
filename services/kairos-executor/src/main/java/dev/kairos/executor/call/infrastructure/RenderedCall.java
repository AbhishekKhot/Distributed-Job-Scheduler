package dev.kairos.executor.call.infrastructure;

import dev.kairos.http.auth.AuthMaterial;
import dev.kairos.http.client.OutboundHttpClient;
import dev.kairos.http.request.OutboundRequest;
import java.util.List;
import java.util.Objects;

/** A fully rendered request and the short-lived material needed to redact its outcome. */
public record RenderedCall(
        OutboundRequest request, AuthMaterial auth, OutboundHttpClient client, List<String> secrets) {
    /** Copies redaction strings and rejects null components. */
    public RenderedCall {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(client, "client");
        secrets = List.copyOf(secrets);
    }

    @Override
    public String toString() {
        return "RenderedCall[request=" + request + ", auth=" + auth.type() + ", secrets=<redacted>]";
    }
}
