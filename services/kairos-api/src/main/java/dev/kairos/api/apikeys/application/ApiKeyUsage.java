package dev.kairos.api.apikeys.application;

import dev.kairos.domain.id.TeamId;

/** Records authenticated API key usage with bounded, per-key write frequency. */
public interface ApiKeyUsage {
    void record(TeamId team, String keyId);
}
