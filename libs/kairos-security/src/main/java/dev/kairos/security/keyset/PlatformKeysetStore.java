package dev.kairos.security.keyset;

import java.time.Instant;
import java.util.Optional;

/** Storage of the platform keysets ({@code platform_keysets} in the control database). */
public interface PlatformKeysetStore {

    /**
     * Reads a platform keyset.
     *
     * @param purpose the purpose
     * @return the sealed keyset, or empty if none has been created
     */
    Optional<SealedKeyset> find(PlatformKeyPurpose purpose);

    /**
     * Stores a platform keyset unless one already exists for the purpose. Safe to call concurrently: exactly one
     * caller wins.
     *
     * @param purpose the purpose
     * @param sealed the sealed keyset
     * @param at the creation time
     * @return {@code true} if this call stored the keyset, {@code false} if one already existed
     */
    boolean insertIfAbsent(PlatformKeyPurpose purpose, SealedKeyset sealed, Instant at);
}
