package dev.kairos.api.security.web;

import dev.kairos.api.security.KairosPrincipal;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * The Spring Security authentication of a verified caller. It carries no credentials (the bearer value is never kept
 * after verification) and no authorities: what a caller may do is decided by its team, not by roles.
 */
public final class KairosAuthenticationToken extends AbstractAuthenticationToken {

    private final KairosPrincipal principal;

    /**
     * Creates an authenticated token.
     *
     * @param principal the verified caller
     */
    public KairosAuthenticationToken(KairosPrincipal principal) {
        super(List.of());
        this.principal = Objects.requireNonNull(principal, "principal");
        setAuthenticated(true);
    }

    @Override
    public KairosPrincipal getPrincipal() {
        return principal;
    }

    /** Always empty: the credential is not kept after verification. */
    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public String getName() {
        return principal.actor();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KairosAuthenticationToken that && principal.equals(that.principal);
    }

    @Override
    public int hashCode() {
        return principal.hashCode();
    }
}
