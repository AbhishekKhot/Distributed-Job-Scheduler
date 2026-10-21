package dev.kairos.api.support.idempotency;

import dev.kairos.api.support.problem.ProblemWriter;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Buffers the request and response of {@code POST} requests that carry an {@code Idempotency-Key}, so the
 * {@link IdempotencyInterceptor} can hash the body and the response of the first request can be stored.
 *
 * <p>The body is read once, up to {@code maxBodyBytes}; a larger body is refused with 400 before any work is done.
 * After the request, if the interceptor claimed the key, a 2xx response is stored for replay and any other outcome
 * (an error status or an exception) frees the key so the client can retry. A failure to store is logged and never
 * changes the response.
 */
public final class IdempotencyFilter extends OncePerRequestFilter {

    /** The request header. */
    public static final String HEADER = "Idempotency-Key";

    /** The request attribute holding the buffered body. */
    public static final String BODY_ATTRIBUTE = IdempotencyFilter.class.getName() + ".body";

    /** The request attribute holding the claim made by the interceptor. */
    public static final String CLAIM_ATTRIBUTE = IdempotencyFilter.class.getName() + ".claim";

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyService service;
    private final ProblemWriter problems;
    private final int maxBodyBytes;

    /**
     * Creates the filter.
     *
     * @param service stores responses and frees keys
     * @param problems writes the problem of an oversized body
     * @param maxBodyBytes the largest body buffered
     */
    public IdempotencyFilter(IdempotencyService service, ProblemWriter problems, int maxBodyBytes) {
        this.service = service;
        this.problems = problems;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || request.getHeader(HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = readBody(request);
        if (body == null) {
            problems.write(request, response, ValidationFailedException.of("body", FieldError.LENGTH));
            return;
        }
        CachedBodyRequest cachedRequest = new CachedBodyRequest(request, body);
        cachedRequest.setAttribute(BODY_ATTRIBUTE, body);
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            chain.doFilter(cachedRequest, cachedResponse);
            completed = true;
        } finally {
            settle(cachedRequest, cachedResponse, completed);
            cachedResponse.copyBodyToResponse();
        }
    }

    private void settle(HttpServletRequest request, ContentCachingResponseWrapper response, boolean completed) {
        if (!(request.getAttribute(CLAIM_ATTRIBUTE) instanceof IdempotencyService.ActiveClaim claim)) {
            return;
        }
        try {
            int status = response.getStatus();
            if (completed && status >= 200 && status < 300) {
                service.finish(claim, storedResponse(response, status));
            } else {
                service.abandon(claim);
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not settle an idempotency record: {}", e.getClass().getSimpleName());
        }
    }

    private static StoredResponse storedResponse(ContentCachingResponseWrapper response, int status) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : StoredResponse.REPLAYED_HEADERS) {
            String value = response.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        byte[] content = response.getContentAsByteArray();
        String body = content.length == 0 ? null : new String(content, StandardCharsets.UTF_8);
        return new StoredResponse(status, response.getContentType(), headers, body);
    }

    // Returns null when the body is larger than allowed.
    private byte @Nullable [] readBody(HttpServletRequest request) throws IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBodyBytes) {
            return null;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(declared > 0 ? (int) declared : 1024);
        try (InputStream in = request.getInputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                if (buffer.size() + read > maxBodyBytes) {
                    return null;
                }
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toByteArray();
    }

    /** A request whose body was read into memory and can be read again. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("asynchronous reads are not supported");
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset =
                    encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding, StandardCharsets.UTF_8);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
