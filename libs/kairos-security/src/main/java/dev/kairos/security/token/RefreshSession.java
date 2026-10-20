package dev.kairos.security.token;

import dev.kairos.domain.id.UserId;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One refresh token's row in {@code user_sessions} (LLD section 2.2). The token itself is never stored, only its
 * SHA-256.
 *
 * <p>Immutable: the hash is copied in and out.
 */
public final class RefreshSession {

    private final UUID sessionId;
    private final UserId user;
    private final UUID familyId;
    private final byte[] tokenHash;
    private final RefreshSessionStatus status;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final @Nullable String userAgent;
    private final @Nullable String ipAddress;

    /**
     * Creates the row.
     *
     * @param sessionId the session id
     * @param user the user
     * @param familyId the rotation family (all rotations of one login)
     * @param tokenHash SHA-256 of the refresh token; copied
     * @param status the status
     * @param createdAt the creation time
     * @param expiresAt the expiry time
     * @param userAgent the client's user agent, at most 256 characters, or {@code null}
     * @param ipAddress the client's address, or {@code null}
     */
    public RefreshSession(
            UUID sessionId,
            UserId user,
            UUID familyId,
            byte[] tokenHash,
            RefreshSessionStatus status,
            Instant createdAt,
            Instant expiresAt,
            @Nullable String userAgent,
            @Nullable String ipAddress) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.user = Objects.requireNonNull(user, "user");
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.tokenHash = tokenHash.clone();
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
    }

    /**
     * Returns the session id.
     *
     * @return the id
     */
    public UUID sessionId() {
        return sessionId;
    }

    /**
     * Returns the user.
     *
     * @return the user
     */
    public UserId user() {
        return user;
    }

    /**
     * Returns the rotation family.
     *
     * @return the family id
     */
    public UUID familyId() {
        return familyId;
    }

    /**
     * Returns the token hash.
     *
     * @return a copy of the SHA-256
     */
    public byte[] tokenHash() {
        return tokenHash.clone();
    }

    /**
     * Returns the status.
     *
     * @return the status
     */
    public RefreshSessionStatus status() {
        return status;
    }

    /**
     * Returns the creation time.
     *
     * @return the time
     */
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * Returns the expiry time.
     *
     * @return the time
     */
    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * Returns the client's user agent.
     *
     * @return the user agent, or {@code null}
     */
    public @Nullable String userAgent() {
        return userAgent;
    }

    /**
     * Returns the client's address.
     *
     * @return the address, or {@code null}
     */
    public @Nullable String ipAddress() {
        return ipAddress;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof RefreshSession that
                && sessionId.equals(that.sessionId)
                && user.equals(that.user)
                && familyId.equals(that.familyId)
                && MessageDigest.isEqual(tokenHash, that.tokenHash)
                && status == that.status
                && createdAt.equals(that.createdAt)
                && expiresAt.equals(that.expiresAt)
                && Objects.equals(userAgent, that.userAgent)
                && Objects.equals(ipAddress, that.ipAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, user, familyId, status, createdAt, expiresAt, userAgent, ipAddress) * 31
                + Arrays.hashCode(tokenHash);
    }

    /** Leaves the hash and client details out. */
    @Override
    public String toString() {
        return "RefreshSession[sessionId=" + sessionId + ", user=" + user + ", familyId=" + familyId + ", status="
                + status + ", expiresAt=" + expiresAt + "]";
    }
}
