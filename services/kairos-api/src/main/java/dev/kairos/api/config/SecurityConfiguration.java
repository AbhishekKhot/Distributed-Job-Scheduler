package dev.kairos.api.config;

import dev.kairos.api.security.BearerAuthenticator;
import dev.kairos.api.security.PublicEndpoints;
import dev.kairos.api.security.web.BearerAuthenticationFilter;
import dev.kairos.api.security.web.ProblemAuthenticationEntryPoint;
import dev.kairos.api.support.problem.ProblemWriter;
import dev.kairos.api.support.ratelimit.RateLimiter;
import dev.kairos.api.support.ratelimit.web.TeamQuotaFilter;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * The Spring Security chain of the API (HLD section 7, IMPLEMENTATION_CHECKLIST section 4.8).
 *
 * <p>Stateless bearer authentication only: no HTTP session, form login, HTTP basic, logout page, request cache or
 * CSRF tokens (the API sets no cookies, so CSRF does not apply). The {@link BearerAuthenticationFilter} accepts an
 * access token or an API key, and the {@link TeamQuotaFilter} counts the team's requests right after. Only the
 * operations of {@link PublicEndpoints}, the OpenAPI document, Swagger UI, the error dispatch and the actuator (on the
 * internal management port) are open; everything else needs a credential, and a missing one is answered with a 401
 * problem. Security headers are set by the {@code SecurityHeadersFilter}, so Spring Security's own header writers are
 * off.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /** Creates the configuration; Spring calls it. */
    public SecurityConfiguration() {}

    /**
     * The API's security filter chain.
     *
     * @param http the builder
     * @param authenticator checks bearer credentials
     * @param limiter the rate limiter, for the team quota
     * @param problems writes problem responses
     * @return the chain
     * @throws Exception if the chain cannot be built
     */
    @Bean
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, BearerAuthenticator authenticator, RateLimiter limiter, ProblemWriter problems)
            throws Exception {
        ProblemAuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(problems);
        BearerAuthenticationFilter bearer = new BearerAuthenticationFilter(authenticator, problems);
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(entryPoint))
                .authorizeHttpRequests(requests -> {
                    for (PublicEndpoints.Endpoint endpoint : PublicEndpoints.ALL) {
                        requests.requestMatchers(HttpMethod.valueOf(endpoint.method()), endpoint.path())
                                .permitAll();
                    }
                    requests.requestMatchers("/error").permitAll();
                    requests.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                            .permitAll();
                    // The actuator is served on the management port, which is not exposed outside the cluster.
                    requests.requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll();
                    requests.anyRequest().authenticated();
                })
                .addFilterBefore(bearer, AnonymousAuthenticationFilter.class)
                .addFilterAfter(new TeamQuotaFilter(limiter, problems), BearerAuthenticationFilter.class);
        return http.build();
    }
}
