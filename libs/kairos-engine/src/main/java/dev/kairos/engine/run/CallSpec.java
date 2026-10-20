package dev.kairos.engine.run;

import dev.kairos.proto.v1.NameValue;
import dev.kairos.proto.v1.RequestSnapshot;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * What a dispatch tells the executor about the call: the timeout of the pinned endpoint version and the request values
 * rendered for the run.
 *
 * <p>The snapshot never carries a secret value (LLD section 4.2). Credentials are named in
 * {@link RequestSnapshot#getSecretRefsList()} and resolved by the executor just before the call, and sensitive
 * upstream outputs travel only in {@code sealed_inputs}. As a backstop, a snapshot whose headers include a header that
 * carries credentials ({@code Authorization}, {@code Proxy-Authorization}, {@code Cookie}, {@code Set-Cookie}) is
 * rejected, so a misrendered request can never put a credential on Kafka.
 *
 * @param timeoutMs the call timeout of the pinned endpoint version, {@value #MIN_TIMEOUT_MS} to
 *     {@value #MAX_TIMEOUT_MS} ms
 * @param request the rendered request values
 */
public record CallSpec(int timeoutMs, RequestSnapshot request) {

    /** Shortest endpoint timeout. */
    public static final int MIN_TIMEOUT_MS = 1_000;

    /** Longest endpoint timeout. */
    public static final int MAX_TIMEOUT_MS = 300_000;

    private static final Set<String> CREDENTIAL_HEADERS =
            Set.of("authorization", "proxy-authorization", "cookie", "set-cookie");

    /**
     * Validates the timeout and the headers.
     *
     * @throws IllegalArgumentException if the timeout is out of range or a header carries credentials
     */
    public CallSpec {
        Objects.requireNonNull(request, "request");
        if (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException(
                    "The call timeout must be " + MIN_TIMEOUT_MS + " to " + MAX_TIMEOUT_MS + " ms");
        }
        for (NameValue header : request.getHeadersList()) {
            if (CREDENTIAL_HEADERS.contains(header.getName().toLowerCase(Locale.ROOT))) {
                // Only the fixed header name is reported, never the value.
                throw new IllegalArgumentException("A request snapshot must not carry the "
                        + header.getName().toLowerCase(Locale.ROOT) + " header");
            }
        }
    }
}
