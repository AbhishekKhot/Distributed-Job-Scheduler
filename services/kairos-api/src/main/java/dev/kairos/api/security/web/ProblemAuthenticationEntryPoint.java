package dev.kairos.api.security.web;

import dev.kairos.api.support.problem.ApiException;
import dev.kairos.api.support.problem.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers a request that needs authentication but has none with 401 {@code unauthenticated} as a problem body
 * (LLD section 3.1). It also answers access-denied decisions the same way: the API has no roles, so the only denial
 * Spring Security can make is for a missing credential.
 */
public final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ProblemWriter problems;

    /**
     * Creates the entry point.
     *
     * @param problems writes problem responses
     */
    public ProblemAuthenticationEntryPoint(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        write(request, response);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException failure)
            throws IOException {
        write(request, response);
    }

    private void write(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        problems.write(request, response, ApiException.unauthenticated());
    }
}
