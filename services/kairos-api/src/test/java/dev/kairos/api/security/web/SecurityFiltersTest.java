package dev.kairos.api.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.security.ApiKeyPrincipal;
import dev.kairos.api.security.UserPrincipal;
import dev.kairos.api.support.problem.ApiException;
import dev.kairos.api.support.problem.ProblemWriter;
import dev.kairos.api.support.problem.TraceIds;
import dev.kairos.api.support.ratelimit.RateLimit;
import dev.kairos.api.support.ratelimit.RateLimiter;
import dev.kairos.api.support.ratelimit.web.TeamQuotaFilter;
import dev.kairos.domain.id.TeamId;
import dev.kairos.testsupport.TestIds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

class SecurityFiltersTest {

    private final ProblemWriter problems = new ProblemWriter(
            JsonMapper.builder().build(),
            new TraceIds(new StaticListableBeanFactory().getBeanProvider(io.micrometer.tracing.Tracer.class)));
    private final TeamId team = TestIds.teamId();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theTeamQuotaIsCountedPerTeam() throws Exception {
        List<String> consumed = new ArrayList<>();
        TeamQuotaFilter filter = new TeamQuotaFilter((limit, subject) -> consumed.add(limit + ":" + subject), problems);
        authenticate(new ApiKeyPrincipal("key", team));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/v1/jobs"), response, chain);

        assertThat(consumed).containsExactly(RateLimit.TEAM_API + ":" + team.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void overTheTeamQuotaTheRequestStops() throws Exception {
        RateLimiter limited = (limit, subject) -> {
            throw ApiException.rateLimited(Duration.ofSeconds(1));
        };
        authenticate(new ApiKeyPrincipal("key", team));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new TeamQuotaFilter(limited, problems).doFilter(new MockHttpServletRequest("GET", "/v1/jobs"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void usersWithoutATeamAndAnonymousRequestsAreNotCounted() throws Exception {
        List<String> consumed = new ArrayList<>();
        TeamQuotaFilter filter = new TeamQuotaFilter((limit, subject) -> consumed.add(subject), problems);

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());
        authenticate(new UserPrincipal(TestIds.userId(), null, UUID.randomUUID()));
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(consumed).isEmpty();
    }

    @Test
    void theEntryPointAnswers401() throws Exception {
        ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(problems);
        MockHttpServletResponse commenced = new MockHttpServletResponse();
        MockHttpServletResponse denied = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest(), commenced, null);
        entryPoint.handle(new MockHttpServletRequest(), denied, null);

        assertThat(commenced.getStatus()).isEqualTo(401);
        assertThat(denied.getStatus()).isEqualTo(401);
        assertThat(denied.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void theTokenCarriesNoCredential() {
        KairosAuthenticationToken token = new KairosAuthenticationToken(new ApiKeyPrincipal("key", team));

        assertThat(token.isAuthenticated()).isTrue();
        assertThat(token.getCredentials()).isEqualTo("");
        assertThat(token.getName()).isEqualTo("key:key");
        assertThat(token).isEqualTo(new KairosAuthenticationToken(new ApiKeyPrincipal("key", team)));
        assertThat(token.hashCode()).isEqualTo(new ApiKeyPrincipal("key", team).hashCode());
    }

    private static void authenticate(dev.kairos.api.security.KairosPrincipal principal) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new KairosAuthenticationToken(principal));
        SecurityContextHolder.setContext(context);
    }
}
