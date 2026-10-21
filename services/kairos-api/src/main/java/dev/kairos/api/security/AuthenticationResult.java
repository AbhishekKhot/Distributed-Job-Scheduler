package dev.kairos.api.security;

import java.util.Objects;

/** The outcome of checking a bearer credential. */
public sealed interface AuthenticationResult {

    /**
     * The credential is valid.
     *
     * @param principal the caller
     */
    record Authenticated(KairosPrincipal principal) implements AuthenticationResult {

        /** Validates the component. */
        public Authenticated {
            Objects.requireNonNull(principal, "principal");
        }
    }

    /**
     * The credential is refused. Every reason is the same 401 {@code unauthenticated} to the client; the reason is for
     * logs only.
     *
     * @param reason why
     */
    record Rejected(Reason reason) implements AuthenticationResult {

        /** Validates the component. */
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Why a credential was refused. */
    enum Reason {
        /** The access token failed verification (signature, algorithm, expiry, claims). */
        INVALID_TOKEN,
        /** The token's login session was revoked or expired, or the user no longer exists. */
        SESSION_REVOKED,
        /** The user was disabled. */
        USER_DISABLED,
        /** The user's team changed since the token was issued; a refresh picks up the new team. */
        TEAM_CHANGED,
        /** The API key is malformed, unknown or its secret does not match. */
        INVALID_API_KEY,
        /** The API key was revoked or expired. */
        API_KEY_UNUSABLE
    }
}
