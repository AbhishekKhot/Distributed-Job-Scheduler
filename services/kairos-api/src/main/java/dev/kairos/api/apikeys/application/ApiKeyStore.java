package dev.kairos.api.apikeys.application;

import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.apikey.IssuedApiKey;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Team-scoped API key persistence. Mutations and their audit entries commit together. */
public interface ApiKeyStore {

    void create(
            TeamId team, UserId creator, String name, IssuedApiKey issued, @Nullable Instant expiresAt, Instant now);

    List<Metadata> list(TeamId team, @Nullable PageKey after, int limit);

    /** Returns false only when the key does not belong to this team; repeated revocation succeeds. */
    boolean revoke(TeamId team, UserId actor, String keyId, Instant now);

    /** Updates usage only if the stored timestamp is at least a minute old. */
    void recordUse(TeamId team, String keyId, Instant now);

    /** Metadata safe to return in lists: neither secret nor hash is loaded. */
    record Metadata(
            String keyId,
            String name,
            UserId createdBy,
            Instant createdAt,
            @Nullable Instant expiresAt,
            @Nullable Instant lastUsedAt,
            @Nullable Instant revokedAt) {
        public PageKey pageKey() {
            return new PageKey(createdAt, keyId);
        }
    }

    /** Stable newest-first pagination key, matching api_keys_team_created_ix. */
    record PageKey(Instant createdAt, String keyId) {}
}
