package dev.kairos.api.support.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WebFiltersTest {

    @Test
    void aValidRequestIdIsKeptAndPutInTheLoggingContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/jobs");
        request.addHeader(RequestIdFilter.HEADER, "abc-123_x.y");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        new RequestIdFilter(new SecureRandom())
                .doFilter(request, response, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
                    @Override
                    protected void service(
                            jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
                        seen.set(MDC.get(RequestIdFilter.MDC_KEY));
                    }
                }));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc-123_x.y");
        assertThat(RequestIdFilter.requestId(request)).isEqualTo("abc-123_x.y");
        assertThat(seen.get()).isEqualTo("abc-123_x.y");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void anInvalidRequestIdIsReplaced() throws Exception {
        for (String invalid : new String[] {"", "x".repeat(65), "a b", "line\nbreak", "<script>"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/jobs");
            request.addHeader(RequestIdFilter.HEADER, invalid);
            MockHttpServletResponse response = new MockHttpServletResponse();

            new RequestIdFilter(new SecureRandom()).doFilter(request, response, new MockFilterChain());

            assertThat(response.getHeader(RequestIdFilter.HEADER)).matches("[0-9a-f]{32}");
        }
        assertThat(RequestIdFilter.requestId(new MockHttpServletRequest())).isEqualTo("unknown");
    }

    @Test
    void securityHeadersAreSetWithASeparatePolicyForSwaggerUi() throws Exception {
        MockHttpServletResponse api = filter("/v1/jobs");
        MockHttpServletResponse swagger = filter("/swagger-ui/index.html");

        assertThat(api.getHeader("Strict-Transport-Security")).startsWith("max-age=");
        assertThat(api.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(api.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(api.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(api.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(api.getHeader("Content-Security-Policy")).isEqualTo(SecurityHeadersFilter.API_POLICY);
        assertThat(swagger.getHeader("Content-Security-Policy")).isEqualTo(SecurityHeadersFilter.SWAGGER_POLICY);
        assertThat(filter("/swagger-ui.html").getHeader("Content-Security-Policy"))
                .isEqualTo(SecurityHeadersFilter.SWAGGER_POLICY);
    }

    private static MockHttpServletResponse filter(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", path), response, new MockFilterChain());
        return response;
    }
}
