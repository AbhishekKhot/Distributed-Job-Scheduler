package dev.kairos.http.client;

import dev.kairos.http.request.HttpMethod;
import java.util.Objects;

/**
 * What is about to be sent, as seen by a {@link SendObserver}. The connection is open and the TLS handshake is done.
 *
 * @param method the request method
 * @param host the target host
 * @param port the target port
 */
public record SendContext(HttpMethod method, String host, int port) {

    /** Validates the components. */
    public SendContext {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(host, "host");
    }
}
