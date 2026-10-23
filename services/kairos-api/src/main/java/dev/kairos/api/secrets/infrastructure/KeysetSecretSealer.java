package dev.kairos.api.secrets.infrastructure;

import dev.kairos.api.secrets.application.CredentialValue;
import dev.kairos.api.secrets.application.SecretSealer;
import dev.kairos.api.secrets.application.SecretStore;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.TeamId;
import dev.kairos.security.cipher.SecretCipher;
import dev.kairos.security.keyset.KeyPurpose;
import dev.kairos.security.keyset.KeysetService;
import java.time.Clock;
import java.util.Arrays;
import tools.jackson.databind.json.JsonMapper;

/** Row-bound AES-GCM encryption using the canonical team keyset, with lazy, race-safe initialization. */
public final class KeysetSecretSealer implements SecretSealer {
    private final SecretStore store;
    private final KeysetService keys;
    private final SecretCipher cipher;
    private final JsonMapper json;
    private final Clock clock;

    public KeysetSecretSealer(
            SecretStore store, KeysetService keys, SecretCipher cipher, JsonMapper json, Clock clock) {
        this.store = store;
        this.keys = keys;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public byte[] seal(TeamId team, String name, int version, CredentialValue value) {
        byte[] plaintext = json.writeValueAsBytes(value.fields());
        try {
            if (plaintext.length > SecretCipher.MAX_PLAINTEXT_BYTES) {
                throw ValidationFailedException.of("value", FieldError.LENGTH);
            }
            var sealed = store.keyset(team)
                    .orElseGet(() ->
                            store.ensureKeyset(team, keys.createTeamKeyset(team, KeyPurpose.SECRETS), clock.instant()));
            return cipher.encryptSecret(keys.aead(team, KeyPurpose.SECRETS, sealed), name, version, plaintext);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }
}
