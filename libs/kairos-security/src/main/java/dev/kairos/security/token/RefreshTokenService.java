package dev.kairos.security.token;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.RandomTokens;
import dev.kairos.security.SecretValue;
import dev.kairos.security.token.RefreshResult.Reason;
import dev.kairos.security.token.RefreshResult.Rejected;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Issues and rotates refresh tokens (HLD section 7, LLD section 3.3).
 *
 * <p>A token is {@code rt_} followed by 32 bytes from the injected {@link SecureRandom} in unpadded base64url. Only its
 * SHA-256 is stored (the token has 256 bits of entropy, so a fast hash is enough; lookups are by hash). Each login
 * starts a family; each refresh marks the presented session {@code ROTATED} and adds a new {@code ACTIVE} session to
 * the family, atomically. Presenting a {@code ROTATED} token again, or losing a race to rotate the same token, means
 * the token was copied: the whole family is revoked, which logs out both the thief and the user.
 *
 * <p>Thread-safe; atomicity of a rotation is the store's ({@link RefreshSessionStore#rotate}).
 */
public final class RefreshTokenService {

    /** The prefix of every refresh token. */
    public static final String PREFIX = "rt_";

    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_LENGTH = PREFIX.length() + 43;
    private static final int MAX_USER_AGENT = 256;

    private final RefreshSessionStore store;
    private final KairosIdFactory ids;
    private final Clock clock;
    private final SecureRandom random;
    private final Duration ttl;

    /**
     * Creates the service.
     *
     * @param store the session store
     * @param ids creates session and family ids
     * @param clock the time source
     * @param random the CSPRNG for tokens
     * @param ttl the session lifetime (30 days by default)
     */
    public RefreshTokenService(
            RefreshSessionStore store, KairosIdFactory ids, Clock clock, SecureRandom random, Duration ttl) {
        this.store = Objects.requireNonNull(store, "store");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("the refresh-token lifetime must be positive");
        }
    }

    /**
     * Starts a new family for a login.
     *
     * @param user the user
     * @param userAgent the client's user agent, truncated to 256 characters, or {@code null}
     * @param ipAddress the client's address, or {@code null}
     * @return the token to return once and its session
     */
    public IssuedRefreshToken issue(UserId user, @Nullable String userAgent, @Nullable String ipAddress) {
        IssuedRefreshToken issued = newSession(user, ids.newId(), userAgent, ipAddress);
        store.insert(issued.session());
        return issued;
    }

    /**
     * Exchanges a refresh token for a new one of the same family.
     *
     * @param presented the token from the request
     * @return the new token, or why the request is refused
     */
    public RefreshResult rotate(String presented) {
        Optional<RefreshSession> found = find(presented);
        if (found.isEmpty()) {
            return new Rejected(Reason.UNKNOWN);
        }
        RefreshSession current = found.get();
        Instant now = clock.instant();
        switch (current.status()) {
            case ROTATED -> {
                // A token is used once. Seeing it again means it was copied: revoke the whole login.
                store.revokeFamily(current.familyId());
                return new Rejected(Reason.REUSED);
            }
            case REVOKED -> {
                return new Rejected(Reason.REVOKED);
            }
            case ACTIVE -> {
                if (!now.isBefore(current.expiresAt())) {
                    return new Rejected(Reason.EXPIRED);
                }
            }
        }
        IssuedRefreshToken next =
                newSession(current.user(), current.familyId(), current.userAgent(), current.ipAddress());
        if (!store.rotate(current, next.session(), now)) {
            // Another request rotated the same token first: the same token was used twice.
            store.revokeFamily(current.familyId());
            return new Rejected(Reason.REUSED);
        }
        return new RefreshResult.Rotated(next, current);
    }

    /**
     * Logs out: revokes the family of a token. Unknown tokens are ignored (logout always answers 204).
     *
     * @param presented the token from the request
     * @return whether a session was found
     */
    public boolean revoke(String presented) {
        Optional<RefreshSession> found = find(presented);
        found.ifPresent(session -> store.revokeFamily(session.familyId()));
        return found.isPresent();
    }

    /**
     * Revokes every session of a user except one family, after a password change.
     *
     * @param user the user
     * @param keepFamilyId the caller's own family, or {@code null} to revoke all
     * @return the number of sessions revoked
     */
    public int revokeOthers(UserId user, @Nullable UUID keepFamilyId) {
        return store.revokeUser(user, keepFamilyId);
    }

    /**
     * Returns the SHA-256 of a token, the lookup key in {@code user_sessions.token_hash}.
     *
     * @param token the token
     * @return the hash
     */
    public static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }

    private Optional<RefreshSession> find(String presented) {
        if (!isWellFormed(presented)) {
            return Optional.empty();
        }
        return store.findByTokenHash(hash(presented));
    }

    private static boolean isWellFormed(String token) {
        if (token.length() != TOKEN_LENGTH || !token.startsWith(PREFIX)) {
            return false;
        }
        for (int i = PREFIX.length(); i < token.length(); i++) {
            if (!RandomTokens.isBase64Url(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private IssuedRefreshToken newSession(
            UserId user, UUID familyId, @Nullable String userAgent, @Nullable String ipAddress) {
        String token = PREFIX + RandomTokens.base64Url(random, TOKEN_BYTES);
        Instant now = clock.instant();
        RefreshSession session = new RefreshSession(
                ids.newId(),
                user,
                familyId,
                hash(token),
                RefreshSessionStatus.ACTIVE,
                now,
                now.plus(ttl),
                userAgent == null || userAgent.length() <= MAX_USER_AGENT
                        ? userAgent
                        : userAgent.substring(0, MAX_USER_AGENT),
                ipAddress);
        return new IssuedRefreshToken(SecretValue.of(token), session);
    }

    @Override
    public String toString() {
        return "RefreshTokenService[ttl=" + ttl + "]";
    }
}
