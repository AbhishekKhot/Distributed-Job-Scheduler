package dev.kairos.security.token;

import dev.kairos.security.SecretValue;
import java.time.Duration;
import java.util.Objects;

/**
 * A newly issued access token.
 *
 * @param token the compact JWS; a bearer credential, redacted in {@link #toString()}
 * @param claims the claims it carries
 */
public record AccessToken(SecretValue token, AccessTokenClaims claims) {

    /** Validates the components. */
    public AccessToken {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(claims, "claims");
    }

    /**
     * Returns the lifetime in seconds, the {@code expiresIn} of the login and refresh responses.
     *
     * @return the seconds from issue to expiry
     */
    public long expiresInSeconds() {
        return Duration.between(claims.issuedAt(), claims.expiresAt()).toSeconds();
    }
}
