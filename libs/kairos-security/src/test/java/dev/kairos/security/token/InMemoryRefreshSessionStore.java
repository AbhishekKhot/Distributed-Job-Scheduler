package dev.kairos.security.token;

import dev.kairos.domain.id.UserId;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** A {@link RefreshSessionStore} in memory, with the same atomic rotation contract as the SQL store. */
public final class InMemoryRefreshSessionStore implements RefreshSessionStore {

    private final Map<ByteBuffer, RefreshSession> sessions = new ConcurrentHashMap<>();
    /** When set, the next rotation fails as if another request had rotated the token first. */
    public boolean loseNextRotation;

    @Override
    public Optional<RefreshSession> findByTokenHash(byte[] tokenHash) {
        return Optional.ofNullable(sessions.get(ByteBuffer.wrap(tokenHash)));
    }

    @Override
    public void insert(RefreshSession session) {
        sessions.put(ByteBuffer.wrap(session.tokenHash()), session);
    }

    @Override
    public synchronized boolean rotate(RefreshSession current, RefreshSession next, Instant at) {
        ByteBuffer key = ByteBuffer.wrap(current.tokenHash());
        RefreshSession stored = sessions.get(key);
        if (loseNextRotation || stored == null || stored.status() != RefreshSessionStatus.ACTIVE) {
            loseNextRotation = false;
            return false;
        }
        sessions.put(key, with(stored, RefreshSessionStatus.ROTATED));
        insert(next);
        return true;
    }

    @Override
    public synchronized int revokeFamily(UUID familyId) {
        return revokeWhere(session -> session.familyId().equals(familyId));
    }

    @Override
    public synchronized int revokeUser(UserId user, @Nullable UUID keepFamilyId) {
        return revokeWhere(
                session -> session.user().equals(user) && !session.familyId().equals(keepFamilyId));
    }

    RefreshSessionStatus status(RefreshSession session) {
        return sessions.get(ByteBuffer.wrap(session.tokenHash())).status();
    }

    private int revokeWhere(java.util.function.Predicate<RefreshSession> filter) {
        int revoked = 0;
        for (Map.Entry<ByteBuffer, RefreshSession> entry : sessions.entrySet()) {
            RefreshSession session = entry.getValue();
            if (filter.test(session) && session.status() != RefreshSessionStatus.REVOKED) {
                entry.setValue(with(session, RefreshSessionStatus.REVOKED));
                revoked++;
            }
        }
        return revoked;
    }

    private static RefreshSession with(RefreshSession session, RefreshSessionStatus status) {
        return new RefreshSession(
                session.sessionId(),
                session.user(),
                session.familyId(),
                session.tokenHash(),
                status,
                session.createdAt(),
                session.expiresAt(),
                session.userAgent(),
                session.ipAddress());
    }
}
