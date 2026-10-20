package dev.kairos.security.keyset;

import com.google.crypto.tink.Aead;
import dev.kairos.domain.id.TeamId;
import java.util.Objects;

/**
 * An opened AEAD keyset of one team and purpose, from {@link KeysetService#aead}.
 *
 * <p>Thread-safe; Tink primitives are.
 *
 * @param team the owning team
 * @param purpose {@link KeyPurpose#SECRETS} or {@link KeyPurpose#OUTPUTS}
 * @param aead the primitive; encrypts with the primary key and decrypts with any key of the keyset
 */
public record TeamAead(TeamId team, KeyPurpose purpose, Aead aead) {

    /**
     * Validates the components.
     *
     * @throws IllegalArgumentException if the purpose is {@link KeyPurpose#SIGNING}
     */
    public TeamAead {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(aead, "aead");
        if (purpose == KeyPurpose.SIGNING) {
            throw new IllegalArgumentException("a signing keyset is not an AEAD");
        }
    }

    /** Leaves the primitive out. */
    @Override
    public String toString() {
        return "TeamAead[team=" + team + ", purpose=" + purpose + "]";
    }
}
