package dev.kairos.api.secrets.application;

import dev.kairos.domain.id.TeamId;
import java.time.Instant;
import java.util.Optional;

/**
 * Credential metadata for endpoint validation (T-204), without loading ciphertext. To create or change a reference,
 * query inside a control transaction after locking the team's row FOR UPDATE, as deletion does.
 */
public interface SecretLookup {
    Optional<Metadata> find(TeamId team, String name);

    record Metadata(String name, CredentialValue.Kind kind, int version, Instant createdAt) {}
}
