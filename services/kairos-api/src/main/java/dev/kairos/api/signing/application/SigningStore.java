package dev.kairos.api.signing.application;

import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.domain.id.TeamId;
import dev.kairos.security.keyset.SealedKeyset;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Versioned signing-key storage. A successful replacement and its audit entry commit atomically. */
public interface SigningStore {
    Optional<Stored> find(TeamId team);

    List<Stored> expired(Instant now, int limit);

    boolean replace(
            Stored expected,
            SealedKeyset replacement,
            @Nullable Instant previousUntil,
            Instant now,
            AuditWriter.Entry audit);

    record Stored(
            TeamId team, SealedKeyset sealed, @Nullable Instant previousUntil) {}
}
