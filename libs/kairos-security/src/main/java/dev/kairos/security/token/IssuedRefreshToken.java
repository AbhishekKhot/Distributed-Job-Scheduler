package dev.kairos.security.token;

import dev.kairos.security.SecretValue;
import java.util.Objects;

/**
 * A newly issued refresh token and the session row that represents it.
 *
 * @param token the token, {@code rt_<43 base64url characters>}, returned to the client once; redacted in
 *     {@link #toString()}
 * @param session the stored session
 */
public record IssuedRefreshToken(SecretValue token, RefreshSession session) {

    /** Validates the components. */
    public IssuedRefreshToken {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(session, "session");
    }
}
