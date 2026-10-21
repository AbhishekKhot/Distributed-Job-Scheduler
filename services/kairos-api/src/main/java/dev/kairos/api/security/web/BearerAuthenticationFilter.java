package dev.kairos.api.security.web;

import dev.kairos.api.security.AuthenticationResult;
import dev.kairos.api.security.BearerAuthenticator;
import dev.kairos.api.security.PublicEndpoints;
import dev.kairos.api.support.problem.ApiException;
import dev.kairos.api.support.problem.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer <access token or API key>} (LLD section 3.1).
 *
 * <p>Without the header the request continues anonymously, and the authorization rules answer 401 for every
 * protected path. A header that is present but malformed or refused is answered with 401 {@code unauthenticated} at
 * once; the response never says why. When the session or key cannot be checked because the control database is
 * unavailable, the request is refused with 503 (fail closed). The credential is never logged or kept.
 *
 * <p>The public operations of {@link PublicEndpoints} skip the filter: a client refreshing its tokens may still send
 * its expired access token, and that must not turn the refresh into a 401.
 */
public final class BearerAuthenticationFilter extends OncePerRequestFilter {

    /** The longest credential read; anything longer is refused before parsing. */
    public static final int MAX_CREDENTIAL_LENGTH = 8192;

    private static final String SCHEME = "Bearer ";
    private static final Logger LOG = LoggerFactory.getLogger(BearerAuthenticationFilter.class);

    private final BearerAuthenticator authenticator;
    private final ProblemWriter problems;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    /**
     * Creates the filter.
     *
     * @param authenticator checks credentials
     * @param problems writes problem responses
     */
    public BearerAuthenticationFilter(BearerAuthenticator authenticator, ProblemWriter problems) {
        this.authenticator = authenticator;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return PublicEndpoints.isPublic(request.getMethod(), path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        if (header.length() <= SCHEME.length()
                || header.length() > MAX_CREDENTIAL_LENGTH + SCHEME.length()
                || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            unauthenticated(request, response);
            return;
        }
        AuthenticationResult result;
        try {
            result =
                    authenticator.authenticate(header.substring(SCHEME.length()).strip());
        } catch (RuntimeException e) {
            LOG.warn(
                    "Credential check failed, refusing the request: {}",
                    e.getClass().getSimpleName());
            problems.write(request, response, ApiException.unavailable(ProblemWriter.DEFAULT_UNAVAILABLE_RETRY));
            return;
        }
        if (!(result instanceof AuthenticationResult.Authenticated authenticated)) {
            if (result instanceof AuthenticationResult.Rejected rejected) {
                LOG.debug("Credential refused: {}", rejected.reason());
            }
            unauthenticated(request, response);
            return;
        }
        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(new KairosAuthenticationToken(authenticated.principal()));
        contexts.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            contexts.clearContext();
        }
    }

    private void unauthenticated(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        problems.write(request, response, ApiException.unauthenticated());
    }
}
