package dev.kairos.api.signing.application;

import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.support.problem.ApiException;
import dev.kairos.domain.id.TeamId;
import dev.kairos.security.SecretValue;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.NewSigningKey;
import dev.kairos.security.keyset.SealedKeyset;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * LLD API 23 and overlap expiry. KEK operations happen outside database transactions. An optimistic comparison
 * prevents concurrent rotations and cleanup from overwriting a newer keyset. Thread-safe.
 */
public final class SigningUseCase {
    private final SigningStore store;
    private final KeysetService keysets;
    private final Clock clock;
    private final Duration overlap;

    public SigningUseCase(SigningStore store, KeysetService keysets, Clock clock, Duration overlap) {
        this.store = store;
        this.keysets = keysets;
        this.clock = clock;
        this.overlap = overlap;
    }

    public Rotated rotate(TeamId team, String actor) {
        for (int retry = 0; retry < 8; retry++) {
            SigningStore.Stored current = store.find(team).orElseThrow(() -> unavailable());
            NewSigningKey rotated = keysets.rotateSigningKey(team, current.sealed());
            Instant now = clock.instant();
            Instant previousUntil = now.plus(overlap);
            AuditWriter.Entry event = new AuditWriter.Entry(
                    team,
                    actor,
                    "SIGNING_KEY_ROTATED",
                    "team",
                    team.value().toString(),
                    Map.of("keyVersion", rotated.version(), "previousKeyValidUntil", previousUntil.toString()));
            if (store.replace(current, rotated.sealed(), previousUntil, now, event)) {
                return new Rotated(rotated.exportedKey(), rotated.version(), previousUntil);
            }
        }
        throw unavailable();
    }

    public int pruneExpired(int batchSize) {
        Instant now = clock.instant();
        int count = 0;
        for (SigningStore.Stored current : store.expired(now, batchSize)) {
            SealedKeyset pruned = keysets.pruneSigningKeys(current.team(), current.sealed());
            AuditWriter.Entry event = new AuditWriter.Entry(
                    current.team(),
                    "system:signing-key-pruner",
                    "SIGNING_KEY_PRUNED",
                    "team",
                    current.team().value().toString(),
                    Map.of());
            if (store.replace(current, pruned, null, now, event)) count++;
        }
        return count;
    }

    private static ApiException unavailable() {
        return ApiException.unavailable(Duration.ofSeconds(1));
    }

    /** The full key is shown once and stays redacted in diagnostics. */
    public record Rotated(SecretValue signingKey, int keyVersion, Instant previousKeyValidUntil) {}
}
