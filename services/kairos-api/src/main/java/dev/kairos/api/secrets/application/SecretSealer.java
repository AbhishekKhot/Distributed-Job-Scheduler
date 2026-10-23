package dev.kairos.api.secrets.application;

import dev.kairos.domain.id.TeamId;

/** Serializes and seals a validated credential; implementations erase their temporary plaintext bytes. */
public interface SecretSealer {
    byte[] seal(TeamId team, String name, int version, CredentialValue value);
}
