package dev.kairos.api.security.web;

import dev.kairos.api.security.KairosPrincipal;
import dev.kairos.api.security.TeamContext;
import dev.kairos.api.security.UserPrincipal;
import dev.kairos.api.support.problem.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Hands controllers the caller of the request, from the verified credential only.
 *
 * <ul>
 *   <li>{@link TeamContext}: the caller's team; a user without a team gets 403 {@code no-team}. Every team-scoped
 *       operation declares this parameter.
 *   <li>{@link UserPrincipal}: the calling user; an API key gets 403 {@code forbidden-for-api-key}.
 *   <li>{@link KairosPrincipal}: the caller, user or key.
 * </ul>
 */
public final class PrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return type == TeamContext.class || type == UserPrincipal.class || type == KairosPrincipal.class;
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        KairosPrincipal principal = CurrentPrincipal.get().orElseThrow(ApiException::unauthenticated);
        Class<?> type = parameter.getParameterType();
        if (type == TeamContext.class) {
            return new TeamContext(principal.team().orElseThrow(ApiException::noTeam), principal);
        }
        if (type == UserPrincipal.class) {
            if (principal instanceof UserPrincipal user) {
                return user;
            }
            throw ApiException.forbiddenForApiKey();
        }
        return principal;
    }
}
