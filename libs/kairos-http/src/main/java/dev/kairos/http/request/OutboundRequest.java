package dev.kairos.http.request;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A fully rendered request to a target: method, URL, headers, body and the endpoint's response timeout.
 *
 * <p>The URL is only checked for shape here; the target policy is applied by the client right before the call.
 * Headers the client manages itself ({@code Host}, {@code Content-Length}, {@code Transfer-Encoding},
 * {@code Connection}, {@code Expect}, ...) are refused, so a template cannot smuggle a second request or change the
 * framing. {@link #toString()} shows neither header values nor the body.
 *
 * <p>Immutable and thread-safe; the body is copied in and out.
 */
public final class OutboundRequest {

    /** Largest accepted request body. */
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    /** Shortest endpoint timeout (LLD section 3.5: {@code timeoutMs} 1000 to 300000). */
    public static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);

    /** Longest endpoint timeout. */
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);

    /** Largest number of headers. */
    public static final int MAX_HEADERS = 100;

    /** Headers the client sets itself and a caller may not. */
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
            "expect");

    private static final String UNRESERVED = "-._~";

    private final HttpMethod method;
    private final URI url;
    private final List<HeaderField> headers;
    private final byte[] body;
    private final Duration timeout;

    private OutboundRequest(HttpMethod method, URI url, List<HeaderField> headers, byte[] body, Duration timeout) {
        this.method = Objects.requireNonNull(method, "method");
        this.url = requireAbsolute(url);
        this.timeout = requireTimeout(timeout);
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("a request body is at most " + MAX_BODY_BYTES + " bytes");
        }
        if (headers.size() > MAX_HEADERS) {
            throw new IllegalArgumentException("a request has at most " + MAX_HEADERS + " headers");
        }
        for (HeaderField header : headers) {
            if (isManagedHeader(header.name())) {
                throw new IllegalArgumentException("the client manages the " + header.name() + " header");
            }
        }
        this.headers = List.copyOf(headers);
        this.body = body.clone();
    }

    /**
     * Starts a request.
     *
     * @param method the method
     * @param url the absolute URL
     * @param timeout the endpoint's response timeout, 1s to 5 min
     * @return a builder
     */
    public static Builder builder(HttpMethod method, URI url, Duration timeout) {
        return new Builder(method, url, timeout);
    }

    /**
     * Tells whether the client manages a header itself, so a caller may not set it.
     *
     * @param name the header name
     * @return true if it is a managed header
     */
    public static boolean isManagedHeader(String name) {
        return MANAGED_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the method.
     *
     * @return the method
     */
    public HttpMethod method() {
        return method;
    }

    /**
     * Returns the URL.
     *
     * @return the absolute URL
     */
    public URI url() {
        return url;
    }

    /**
     * Returns the headers in order.
     *
     * @return the unmodifiable headers
     */
    public List<HeaderField> headers() {
        return headers;
    }

    /**
     * Returns the first value of a header.
     *
     * @param name the header name, any case
     * @return the value, or empty if the header is absent
     */
    public Optional<String> header(String name) {
        return headers.stream()
                .filter(header -> header.hasName(name))
                .map(HeaderField::value)
                .findFirst();
    }

    /**
     * Returns a copy of the body.
     *
     * @return the body, empty when there is none
     */
    public byte[] body() {
        return body.clone();
    }

    /**
     * Returns the body length.
     *
     * @return the number of body bytes
     */
    public int bodyLength() {
        return body.length;
    }

    /**
     * Returns the endpoint's response timeout.
     *
     * @return the timeout
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns a copy with a header set, replacing every header of the same name.
     *
     * @param header the header
     * @return the new request
     * @throws IllegalArgumentException if the header is managed by the client
     */
    public OutboundRequest withHeader(HeaderField header) {
        List<HeaderField> copy = new ArrayList<>(headers.size() + 1);
        for (HeaderField existing : headers) {
            if (!existing.hasName(header.name())) {
                copy.add(existing);
            }
        }
        copy.add(header);
        return new OutboundRequest(method, url, copy, body, timeout);
    }

    /**
     * Returns a copy with a query parameter appended. The name and value are percent-encoded (RFC 3986 unreserved
     * characters stay as they are), so neither can add parameters or change the path.
     *
     * @param name the parameter name, not empty
     * @param value the parameter value
     * @return the new request
     */
    public OutboundRequest withQueryParameter(String name, String value) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("a query parameter name is not empty");
        }
        String pair = percentEncode(name) + "=" + percentEncode(value);
        String query = url.getRawQuery();
        String newQuery = query == null || query.isEmpty() ? pair : query + "&" + pair;
        try {
            URI changed = new URI(url.getScheme() + "://" + url.getRawAuthority() + nullToEmpty(url.getRawPath()) + "?"
                    + newQuery + (url.getRawFragment() == null ? "" : "#" + url.getRawFragment()));
            return new OutboundRequest(method, changed, headers, body, timeout);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("the URL cannot take a query parameter", e);
        }
    }

    /** Shows the method, scheme, host and timeout only; the path and query may carry identifiers or keys. */
    @Override
    public String toString() {
        return "OutboundRequest[method=" + method + ", host=" + url.getHost() + ", timeout=" + timeout + "]";
    }

    /**
     * Percent-encodes a string for a URL component: everything but RFC 3986 unreserved characters is encoded as UTF-8.
     *
     * @param text the text
     * @return the encoded text
     */
    public static String percentEncode(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || UNRESERVED.indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    private static String nullToEmpty(@Nullable String text) {
        return text == null ? "" : text;
    }

    private static URI requireAbsolute(URI url) {
        Objects.requireNonNull(url, "url");
        if (!url.isAbsolute() || url.isOpaque() || url.getRawAuthority() == null) {
            throw new IllegalArgumentException("a request URL is absolute and hierarchical");
        }
        return url;
    }

    private static Duration requireTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("a request timeout is 1s to 5min");
        }
        return timeout;
    }

    /** Builds an {@link OutboundRequest}. Not thread-safe. */
    public static final class Builder {

        private final HttpMethod method;
        private final URI url;
        private final Duration timeout;
        private final List<HeaderField> headers = new ArrayList<>();
        private byte[] body = new byte[0];

        private Builder(HttpMethod method, URI url, Duration timeout) {
            this.method = method;
            this.url = url;
            this.timeout = timeout;
        }

        /**
         * Adds a header.
         *
         * @param name the name
         * @param value the value
         * @return this builder
         * @throws IllegalArgumentException if the name or value is invalid
         */
        public Builder header(String name, String value) {
            headers.add(new HeaderField(name, value));
            return this;
        }

        /**
         * Sets the body; the bytes are copied.
         *
         * @param bytes the body
         * @return this builder
         */
        public Builder body(byte[] bytes) {
            this.body = bytes.clone();
            return this;
        }

        /**
         * Builds the request.
         *
         * @return the request
         * @throws IllegalArgumentException if a part is invalid
         */
        public OutboundRequest build() {
            return new OutboundRequest(method, url, headers, body, timeout);
        }
    }
}
