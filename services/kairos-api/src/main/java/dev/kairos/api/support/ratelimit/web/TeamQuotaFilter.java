package dev.kairos.api.support.ratelimit.web;

import dev.kairos.api.security.KairosPrincipal;
import dev.kairos.api.security.web.CurrentPrincipal;
import dev.kairos.api.support.problem.ApiException;
import dev.kairos.api.support.problem.ProblemWriter;
import dev.kairos.api.support.ratelimit.RateLimit;
import dev.kairos.api.support.ratelimit.RateLimiter;
import dev.kairos.domain.id.TeamId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the per-team API quota ({@link RateLimit#TEAM_API}) to every authenticated request of a team, whether it
 * carries a user token or an API key. Runs right after authentication. Over the quota the request gets 429
 * {@code rate-limited} with {@code Retry-After}; when Redis is unavailable it gets 503 (fail closed). Requests of
 * users without a team are not counted here; they can only reach account operations, which have their own limits.
 */
public final class TeamQuotaFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final ProblemWriter problems;

    /**
     * Creates the filter.
     *
     * @param limiter the rate limiter
     * @param problems writes problem responses
     */
    public TeamQuotaFilter(RateLimiter limiter, ProblemWriter problems) {
        this.limiter = limiter;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<TeamId> team = CurrentPrincipal.get().flatMap(KairosPrincipal::team);
        if (team.isPresent()) {
            try {
                limiter.consume(RateLimit.TEAM_API, team.get().value().toString());
            } catch (ApiException e) {
                problems.write(request, response, e);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
