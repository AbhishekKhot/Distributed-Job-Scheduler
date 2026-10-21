package dev.kairos.api.config;

import dev.kairos.api.security.web.PrincipalArgumentResolver;
import dev.kairos.api.security.web.UserTokenOnlyInterceptor;
import dev.kairos.api.support.etag.IfMatchArgumentResolver;
import dev.kairos.api.support.idempotency.IdempotencyFilter;
import dev.kairos.api.support.idempotency.IdempotencyInterceptor;
import dev.kairos.api.support.idempotency.IdempotencyService;
import dev.kairos.api.support.problem.ProblemWriter;
import dev.kairos.api.support.web.RequestIdFilter;
import dev.kairos.api.support.web.SecurityHeadersFilter;
import jakarta.servlet.DispatcherType;
import java.security.SecureRandom;
import java.util.List;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Servlet filters, interceptors and argument resolvers of the API foundation.
 *
 * <p>Filter order: the request id first (so every response, even a rejected one, has an id), then the security
 * headers, then Spring Security (bearer authentication and the team quota), then the idempotency buffer. Interceptors
 * run before the controller: the user-token check, then idempotency (so an API key never claims a key on a
 * user-token-only operation). Argument resolvers supply {@code TeamContext}, the principal and {@code @IfMatch}.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebMvcConfigurer {

    /** The order of the request id filter: first of all. */
    static final int REQUEST_ID_ORDER = Ordered.HIGHEST_PRECEDENCE;

    /** The order of the security headers filter: after tracing starts, before security. */
    static final int SECURITY_HEADERS_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /** The order of the idempotency buffer: after Spring Security. */
    static final int IDEMPOTENCY_ORDER = SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10;

    private final IdempotencyService idempotency;
    private final JsonMapper json;

    /**
     * Creates the configuration.
     *
     * @param idempotency the idempotency rules
     * @param json the JSON mapper
     */
    public WebConfiguration(IdempotencyService idempotency, JsonMapper json) {
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserTokenOnlyInterceptor()).order(0);
        registry.addInterceptor(new IdempotencyInterceptor(idempotency, json)).order(10);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PrincipalArgumentResolver());
        resolvers.add(new IfMatchArgumentResolver());
    }

    /**
     * Registers the request id filter.
     *
     * @param random the CSPRNG for generated ids
     * @return the registration
     */
    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter(SecureRandom random) {
        FilterRegistrationBean<RequestIdFilter> registration =
                new FilterRegistrationBean<>(new RequestIdFilter(random));
        registration.setOrder(REQUEST_ID_ORDER);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        return registration;
    }

    /**
     * Registers the security headers filter.
     *
     * @return the registration
     */
    @Bean
    public FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        FilterRegistrationBean<SecurityHeadersFilter> registration =
                new FilterRegistrationBean<>(new SecurityHeadersFilter());
        registration.setOrder(SECURITY_HEADERS_ORDER);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        return registration;
    }

    /**
     * Registers the idempotency buffer.
     *
     * @param problems writes the problem of an oversized body
     * @param properties the API settings
     * @return the registration
     */
    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(
            ProblemWriter problems, ApiProperties properties) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(new IdempotencyFilter(
                idempotency, problems, properties.idempotency().maxBodyBytes()));
        registration.setOrder(IDEMPOTENCY_ORDER);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        return registration;
    }
}
