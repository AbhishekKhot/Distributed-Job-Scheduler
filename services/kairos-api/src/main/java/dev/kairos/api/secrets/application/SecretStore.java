package dev.kairos.api.secrets.application;

import dev.kairos.domain.id.TeamId;
import dev.kairos.security.keyset.SealedKeyset;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Atomic credential mutations and audit entries. Rotation compares versions to avoid lost updates. */
public interface SecretStore extends SecretLookup {
    Optional<SealedKeyset> keyset(TeamId team);

    SealedKeyset ensureKeyset(TeamId team, SealedKeyset candidate, Instant now);

    Metadata create(TeamId team, String actor, String name, CredentialValue.Kind kind, byte[] ciphertext, Instant now);

    Optional<Metadata> rotate(TeamId team, String actor, Metadata expected, byte[] ciphertext, Instant now);

    List<Metadata> list(TeamId team, @Nullable String after, int limit);

    void delete(TeamId team, String actor, String name);
}
