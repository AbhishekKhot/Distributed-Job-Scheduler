package dev.kairos.http.auth;

import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.TeamId;
import java.util.Objects;

/**
 * Identity of a cached OAuth 2.0 access token. Endpoint versions and secret versions are immutable, so a new token URL,
 * scope or client secret always gets a new key and an old token is never reused for it.
 *
 * @param teamId the team
 * @param endpointId the endpoint
 * @param endpointVersion the pinned endpoint version
 * @param secretVersion the version of the client-credentials secret
 */
public record TokenCacheKey(TeamId teamId, EndpointId endpointId, int endpointVersion, int secretVersion) {

    /**
     * Validates the key.
     *
     * @throws IllegalArgumentException if a version is not positive
     */
    public TokenCacheKey {
        Objects.requireNonNull(teamId, "teamId");
        Objects.requireNonNull(endpointId, "endpointId");
        if (endpointVersion < 1 || secretVersion < 1) {
            throw new IllegalArgumentException("versions start at 1");
        }
    }
}
