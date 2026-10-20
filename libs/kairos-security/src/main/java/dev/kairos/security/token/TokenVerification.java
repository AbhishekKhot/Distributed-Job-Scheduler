package dev.kairos.security.token;

import java.util.Objects;

/** The result of verifying an access token: the claims, or why it was rejected. */
public sealed interface TokenVerification {

    /**
     * The token is valid.
     *
     * @param claims the verified claims
     */
    record Valid(AccessTokenClaims claims) implements TokenVerification {

        /** Validates the component. */
        public Valid {
            Objects.requireNonNull(claims, "claims");
        }
    }

    /**
     * The token is rejected. The reason is for metrics and logs only; every reason is the same 401 to the client.
     *
     * @param reason why
     */
    record Invalid(Reason reason) implements TokenVerification {

        /** Validates the component. */
        public Invalid {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Why a token was rejected. */
    enum Reason {
        /** Not a compact JWS, too long, or not parseable. */
        MALFORMED,
        /** The header names an algorithm other than ES256 (including {@code none} and HMAC). */
        ALGORITHM,
        /** The {@code kid} is missing or not one of the platform's keys. */
        UNKNOWN_KEY,
        /** The signature does not verify. */
        SIGNATURE,
        /** The issuer is wrong. */
        ISSUER,
        /** The audience does not include this service. */
        AUDIENCE,
        /** The token expired (beyond the allowed clock skew). */
        EXPIRED,
        /** The token was issued in the future (beyond the allowed clock skew). */
        NOT_YET_VALID,
        /** A required claim is missing or malformed. */
        CLAIMS
    }
}
