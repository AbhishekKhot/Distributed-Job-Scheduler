package dev.kairos.api.security;

import dev.kairos.domain.id.TeamId;
import java.util.Objects;
import java.util.Optional;

/**
 * A team API key.
 *
 * @param keyId the public key id
 * @param teamId the key's team
 */
public record ApiKeyPrincipal(String keyId, TeamId teamId) implements KairosPrincipal {

    /** Validates the components. */
    public ApiKeyPrincipal {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(teamId, "teamId");
    }

    @Override
    public Optional<TeamId> team() {
        return Optional.of(teamId);
    }

    @Override
    public String actor() {
        return "key:" + keyId;
    }
}
