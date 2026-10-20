package dev.kairos.security.token;

import dev.kairos.domain.id.UserId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Storage of refresh-token sessions ({@code user_sessions} in the control database). The API implements it with SQL;
 * the rotation rules live in {@link RefreshTokenService}.
 */
public interface RefreshSessionStore {

    /**
     * Finds the session of a token.
     *
     * @param tokenHash SHA-256 of the presented token
     * @return the session, or empty if the hash is unknown
     */
    Optional<RefreshSession> findByTokenHash(byte[] tokenHash);

    /**
     * Stores a new session (a login).
     *
     * @param session the session
     */
    void insert(RefreshSession session);

    /**
     * Rotates atomically: marks {@code current} {@code ROTATED} if it is still {@code ACTIVE} and, in the same
     * transaction, inserts {@code next}. Two concurrent refreshes with the same token therefore cannot both succeed.
     *
     * @param current the session being exchanged
     * @param next the new session of the same family
     * @param at the time of use, recorded as {@code last_used_at}
     * @return {@code true} if rotated; {@code false} (and nothing written) if {@code current} was no longer active
     */
    boolean rotate(RefreshSession current, RefreshSession next, Instant at);

    /**
     * Revokes every session of a family.
     *
     * @param familyId the family
     * @return the number of sessions revoked
     */
    int revokeFamily(UUID familyId);

    /**
     * Revokes every session of a user, optionally keeping one family (the caller's own, on password change).
     *
     * @param user the user
     * @param keepFamilyId the family to keep, or {@code null} to revoke all
     * @return the number of sessions revoked
     */
    int revokeUser(UserId user, @Nullable UUID keepFamilyId);
}
