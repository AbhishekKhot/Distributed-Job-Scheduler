package dev.kairos.api.support.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id, returned in {@code X-Request-Id} and put in the logging context as {@code requestId}.
 *
 * <p>A caller's own {@code X-Request-Id} is kept when it is 1 to 64 characters of {@code A-Z a-z 0-9 . _ -}; anything
 * else (too long, control characters, other symbols) is replaced by 32 random hex characters, so the header can never
 * be used to inject text into logs or responses. Runs first, before security, so even a rejected request has an id.
 */
public final class RequestIdFilter extends OncePerRequestFilter {

    /** The request and response header. */
    public static final String HEADER = "X-Request-Id";

    /** The logging context key. */
    public static final String MDC_KEY = "requestId";

    /** The request attribute holding the id. */
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

    /** The longest accepted incoming id. */
    public static final int MAX_LENGTH = 64;

    private static final int GENERATED_BYTES = 16;

    private final SecureRandom random;

    /**
     * Creates the filter.
     *
     * @param random the source of generated ids
     */
    public RequestIdFilter(SecureRandom random) {
        this.random = random;
    }

    /**
     * Returns the id of a request.
     *
     * @param request the request
     * @return the id, or {@code "unknown"} if the filter has not run
     */
    public static String requestId(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof String id ? id : "unknown";
    }

    /**
     * Tells whether an incoming id is acceptable.
     *
     * @param value the header value
     * @return whether it is 1 to 64 allowed characters
     */
    public static boolean isValid(@Nullable String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = isValid(incoming) ? incoming : generate();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private String generate() {
        byte[] bytes = new byte[GENERATED_BYTES];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
