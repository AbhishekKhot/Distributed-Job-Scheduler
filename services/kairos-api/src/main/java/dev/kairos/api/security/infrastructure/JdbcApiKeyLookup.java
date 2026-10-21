package dev.kairos.api.security.infrastructure;

import dev.kairos.api.security.ApiKeyLookup;
import dev.kairos.domain.id.TeamId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link ApiKeyLookup} on {@code api_keys}, by primary key. Thread-safe. */
public final class JdbcApiKeyLookup implements ApiKeyLookup {

    private static final String FIND = """
            SELECT key_id, team_id, secret_hash, pepper_version, expires_at, revoked_at
              FROM scheduler.api_keys
             WHERE key_id = :keyId
            """;
    private static final String UPGRADE = """
            UPDATE scheduler.api_keys
               SET secret_hash = :hash, pepper_version = :newVersion
             WHERE key_id = :keyId AND pepper_version = :oldVersion
            """;

    private final JdbcClient control;

    /**
     * Creates the lookup.
     *
     * @param control the control database's client
     */
    public JdbcApiKeyLookup(JdbcClient control) {
        this.control = Objects.requireNonNull(control, "control");
    }

    @Override
    public Optional<StoredApiKey> find(String keyId) {
        return control.sql(FIND)
                .param("keyId", keyId)
                .query((rs, row) -> new StoredApiKey(
                        rs.getString("key_id"),
                        new TeamId(rs.getObject("team_id", UUID.class)),
                        rs.getBytes("secret_hash"),
                        rs.getInt("pepper_version"),
                        SqlTimes.instant(rs, "expires_at"),
                        SqlTimes.instant(rs, "revoked_at")))
                .optional();
    }

    @Override
    public void upgradeHash(String keyId, int oldPepperVersion, byte[] newHash, int newPepperVersion) {
        control.sql(UPGRADE)
                .param("keyId", keyId)
                .param("hash", newHash)
                .param("newVersion", (short) newPepperVersion)
                .param("oldVersion", (short) oldPepperVersion)
                .update();
    }
}
