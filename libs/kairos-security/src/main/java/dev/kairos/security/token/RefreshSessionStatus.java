package dev.kairos.security.token;

/** The {@code status} of a {@code user_sessions} row. */
public enum RefreshSessionStatus {
    /** The current token of its family; it can be used once. */
    ACTIVE,
    /** Already exchanged for a newer token; presenting it again revokes the family. */
    ROTATED,
    /** Logged out, revoked after reuse or after a password change. */
    REVOKED
}
