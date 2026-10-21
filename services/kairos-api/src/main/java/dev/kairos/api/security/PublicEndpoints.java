package dev.kairos.api.security;

import java.util.List;
import java.util.Objects;

/**
 * The only operations that need no credential (LLD section 3.1): signup, login, refresh and the JWKS document. The
 * security configuration permits exactly these, and the OpenAPI document marks exactly these as public.
 */
public final class PublicEndpoints {

    /** Every public operation. */
    public static final List<Endpoint> ALL = List.of(
            new Endpoint("POST", "/v1/auth/signup"),
            new Endpoint("POST", "/v1/auth/login"),
            new Endpoint("POST", "/v1/auth/refresh"),
            new Endpoint("GET", "/.well-known/jwks.json"));

    private PublicEndpoints() {}

    /**
     * Tells whether an operation is public.
     *
     * @param method the HTTP method, upper case
     * @param path the path template
     * @return whether it needs no credential
     */
    public static boolean isPublic(String method, String path) {
        return ALL.contains(new Endpoint(method, path));
    }

    /**
     * One public operation.
     *
     * @param method the HTTP method, upper case
     * @param path the exact path
     */
    public record Endpoint(String method, String path) {

        /** Validates the components. */
        public Endpoint {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
        }
    }
}
