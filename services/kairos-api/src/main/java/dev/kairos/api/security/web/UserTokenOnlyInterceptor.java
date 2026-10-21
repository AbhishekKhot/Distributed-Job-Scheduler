package dev.kairos.api.security.web;

import dev.kairos.api.security.ApiKeyPrincipal;
import dev.kairos.api.security.UserTokenOnly;
import dev.kairos.api.support.problem.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rejects API keys on operations marked {@link UserTokenOnly} with 403 {@code forbidden-for-api-key}, before the
 * controller method runs.
 */
public final class UserTokenOnlyInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method
                && isUserTokenOnly(method)
                && CurrentPrincipal.get().orElse(null) instanceof ApiKeyPrincipal) {
            throw ApiException.forbiddenForApiKey();
        }
        return true;
    }

    /**
     * Tells whether an operation is reserved for user tokens.
     *
     * @param method the controller method
     * @return whether it or its controller carries {@link UserTokenOnly}
     */
    public static boolean isUserTokenOnly(HandlerMethod method) {
        return method.hasMethodAnnotation(UserTokenOnly.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), UserTokenOnly.class);
    }
}
