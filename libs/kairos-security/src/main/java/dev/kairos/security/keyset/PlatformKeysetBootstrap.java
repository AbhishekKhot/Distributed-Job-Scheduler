package dev.kairos.security.keyset;

import java.time.Clock;
import java.util.Objects;

/**
 * Creates the platform keysets that must exist before the API can issue tokens. The migrations job runs it after the
 * schema is in place.
 *
 * <p>Idempotent and safe to run concurrently: an existing keyset is never replaced (rotation is a separate operator
 * action), and when two runs race, the store's insert-if-absent keeps exactly one.
 */
public final class PlatformKeysetBootstrap {

    private final PlatformKeysetStore store;
    private final KeysetService keysets;
    private final Clock clock;

    /**
     * Creates the bootstrap.
     *
     * @param store the platform keyset store
     * @param keysets creates and seals keysets
     * @param clock the time recorded as {@code rotated_at}
     */
    public PlatformKeysetBootstrap(PlatformKeysetStore store, KeysetService keysets, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.keysets = Objects.requireNonNull(keysets, "keysets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates the access-token signing keyset if it is missing.
     *
     * @return {@code true} if this call created it, {@code false} if it already existed
     */
    public boolean ensureJwtSigningKeyset() {
        if (store.find(PlatformKeyPurpose.JWT_SIGNING).isPresent()) {
            return false;
        }
        return store.insertIfAbsent(PlatformKeyPurpose.JWT_SIGNING, keysets.createJwtSigningKeyset(), clock.instant());
    }
}
