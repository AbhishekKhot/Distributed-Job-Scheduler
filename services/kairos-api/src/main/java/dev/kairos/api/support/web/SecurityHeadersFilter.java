package dev.kairos.api.support.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sets the security headers of IMPLEMENTATION_CHECKLIST section 4.8 on every response, including errors and
 * responses written by the security filters.
 *
 * <p>The API serves JSON only, so its content security policy allows nothing. Swagger UI is an HTML page with its own
 * scripts and styles, so its paths get a policy that allows same-origin scripts, styles, images and API calls and
 * nothing else. Responses are not cacheable unless a controller says otherwise (for example the JWKS document).
 */
public final class SecurityHeadersFilter extends OncePerRequestFilter {

    /** The policy of every API response. */
    public static final String API_POLICY = "default-src 'none'; frame-ancestors 'none'";

    /** The policy of the Swagger UI pages. */
    public static final String SWAGGER_POLICY =
            "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline';"
                    + " img-src 'self' data:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none';"
                    + " form-action 'none'";

    private static final String HSTS = "max-age=31536000; includeSubDomains";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Strict-Transport-Security", HSTS);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", isSwaggerUi(request) ? SWAGGER_POLICY : API_POLICY);
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * Tells whether a request is for the Swagger UI pages.
     *
     * @param request the request
     * @return whether its path is {@code /swagger-ui.html} or under {@code /swagger-ui/}
     */
    static boolean isSwaggerUi(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/swagger-ui.html") || path.startsWith("/swagger-ui/");
    }
}
