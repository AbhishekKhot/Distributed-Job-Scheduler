package dev.kairos.security.token;

import java.util.Objects;

/** The result of exchanging a refresh token. */
public sealed interface RefreshResult {

    /**
     * The token was exchanged.
     *
     * @param issued the new token and session (same family)
     * @param previous the session that was exchanged
     */
    record Rotated(IssuedRefreshToken issued, RefreshSession previous) implements RefreshResult {

        /** Validates the components. */
        public Rotated {
            Objects.requireNonNull(issued, "issued");
            Objects.requireNonNull(previous, "previous");
        }
    }

    /**
     * The token was refused. Every reason is the same 401 {@code invalid-refresh-token} to the client.
     *
     * @param reason why
     */
    record Rejected(Reason reason) implements RefreshResult {

        /** Validates the component. */
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Why a refresh token was refused. */
    enum Reason {
        /** Not a well-formed token, or no session has its hash. */
        UNKNOWN,
        /** The session expired. */
        EXPIRED,
        /** The session was revoked. */
        REVOKED,
        /** An already rotated token was presented again; the whole family has been revoked. */
        REUSED
    }
}
