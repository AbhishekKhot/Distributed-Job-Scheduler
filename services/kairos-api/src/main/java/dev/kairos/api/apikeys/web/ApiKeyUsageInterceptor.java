package dev.kairos.api.apikeys.web;

import dev.kairos.api.apikeys.application.ApiKeyUsage;
import dev.kairos.api.security.ApiKeyPrincipal;
import dev.kairos.api.security.web.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** Records authenticated API key usage, including calls to user-token-only operations. */
public final class ApiKeyUsageInterceptor implements HandlerInterceptor {
    private final ApiKeyUsage usage;

    public ApiKeyUsageInterceptor(ApiKeyUsage usage) {
        this.usage = usage;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (CurrentPrincipal.get().orElse(null) instanceof ApiKeyPrincipal key) {
            usage.record(key.teamId(), key.keyId());
        }
        return true;
    }
}
