package dev.kairos.security.keyset;

import com.google.crypto.tink.Mac;
import dev.kairos.domain.id.TeamId;
import java.util.Objects;

/**
 * The opened request-signing key of a team, from {@link KeysetService#signingKey}.
 *
 * <p>{@code mac} computes plain HMAC-SHA256 (no Tink output prefix) with the keyset's primary key, so targets can
 * verify with any standard HMAC library and the key they were shown once.
 *
 * <p>Thread-safe; Tink primitives are.
 *
 * @param team the owning team
 * @param version the primary key's version, the {@code n} of {@code ksig_v<n>_...} and of {@code v<n>=} in the
 *     signature header
 * @param mac the primitive
 */
public record TeamSigningKey(TeamId team, int version, Mac mac) {

    /** Validates the components. */
    public TeamSigningKey {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(mac, "mac");
        if (version < 1) {
            throw new IllegalArgumentException("signing key versions start at 1");
        }
    }

    /** Leaves the primitive out. */
    @Override
    public String toString() {
        return "TeamSigningKey[team=" + team + ", version=" + version + "]";
    }
}
