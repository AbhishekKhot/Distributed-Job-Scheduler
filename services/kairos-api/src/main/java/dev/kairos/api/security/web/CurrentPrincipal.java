package dev.kairos.api.security.web;

import dev.kairos.api.security.KairosPrincipal;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the caller of the current request from the security context. */
public final class CurrentPrincipal {

    private CurrentPrincipal() {}

    /**
     * Returns the caller of the current request.
     *
     * @return the principal, or empty for an anonymous request
     */
    public static Optional<KairosPrincipal> get() {
        Authentication authentication =
                SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        if (authentication instanceof KairosAuthenticationToken token) {
            return Optional.of(token.getPrincipal());
        }
        return Optional.empty();
    }
}
