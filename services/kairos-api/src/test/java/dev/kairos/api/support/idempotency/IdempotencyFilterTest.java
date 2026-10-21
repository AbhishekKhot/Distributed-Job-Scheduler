package dev.kairos.api.support.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.support.problem.ProblemWriter;
import dev.kairos.api.support.problem.TraceIds;
import dev.kairos.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class IdempotencyFilterTest {

    private final ProblemWriter problems = new ProblemWriter(
            JsonMapper.builder().build(),
            new TraceIds(new StaticListableBeanFactory().getBeanProvider(io.micrometer.tracing.Tracer.class)));
    private final IdempotencyService service = new IdempotencyService(
            new InMemoryIdempotencyStore(),
            MutableClock.at(Instant.parse("2026-10-21T10:00:00Z")),
            new IdempotencyService.Settings(
                    Duration.ofHours(24), Duration.ofSeconds(60), Duration.ofSeconds(2), Duration.ofMillis(50)),
            duration -> {});

    @Test
    void anOversizedBodyIsRejectedBeforeAnyWork() throws Exception {
        MockHttpServletRequest request = request(new byte[2048]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new IdempotencyFilter(service, problems, 1024).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("{\"field\":\"body\",\"code\":\"length\"}");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void theBodyCanBeReadAgainDownstream() throws Exception {
        MockHttpServletRequest request = request("{\"name\":\"a\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new IdempotencyFilter(service, problems, 1024).doFilter(request, response, chain);

        assertThat(new String(
                        chain.getRequest().getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"name\":\"a\"}");
        assertThat(chain.getRequest().getAttribute(IdempotencyFilter.BODY_ATTRIBUTE))
                .isNotNull();
        assertThat(chain.getRequest().getContentLength()).isEqualTo(12);
    }

    @Test
    void requestsWithoutAKeyOrNotPostArePassedThrough() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/v1/jobs");
        get.addHeader(IdempotencyFilter.HEADER, "k");
        MockFilterChain chain = new MockFilterChain();

        new IdempotencyFilter(service, problems, 1024).doFilter(get, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(get);
    }

    @Test
    void keysAreValidated() {
        IdempotencyInterceptor.requireValidKey("9b1d6f2e-5c3a-4e8f-a1b2-c3d4e5f6a7b8");
        for (String invalid : new String[] {"", "k".repeat(65), "with space", "tab\t"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> IdempotencyInterceptor.requireValidKey(invalid))
                    .isInstanceOf(dev.kairos.domain.error.ValidationFailedException.class);
        }
    }

    private static MockHttpServletRequest request(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/jobs");
        request.addHeader(IdempotencyFilter.HEADER, "key-1");
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }
}
