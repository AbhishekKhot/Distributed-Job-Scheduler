package dev.kairos.security.keyset;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link PlatformKeysetStore} on the control database's {@code scheduler.platform_keysets} table.
 *
 * <p>The insert is {@code ON CONFLICT DO NOTHING}, so concurrent bootstraps (several migration or API pods starting at
 * once) store exactly one keyset. Thread-safe.
 */
public final class JdbcPlatformKeysetStore implements PlatformKeysetStore {

    private static final String FIND = """
            SELECT encrypted_keyset, kek_uri FROM scheduler.platform_keysets WHERE purpose = :purpose
            """;
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO scheduler.platform_keysets (purpose, encrypted_keyset, kek_uri, rotated_at)
            VALUES (:purpose, :keyset, :kekUri, :at)
            ON CONFLICT (purpose) DO NOTHING
            """;

    private final JdbcClient control;

    /**
     * Creates the store.
     *
     * @param control the control database's client
     */
    public JdbcPlatformKeysetStore(JdbcClient control) {
        this.control = Objects.requireNonNull(control, "control");
    }

    @Override
    public Optional<SealedKeyset> find(PlatformKeyPurpose purpose) {
        return control.sql(FIND)
                .param("purpose", purpose.name())
                .query((rs, row) -> new SealedKeyset(rs.getBytes("encrypted_keyset"), rs.getString("kek_uri")))
                .optional();
    }

    @Override
    public boolean insertIfAbsent(PlatformKeyPurpose purpose, SealedKeyset sealed, Instant at) {
        return control.sql(INSERT_IF_ABSENT)
                        .param("purpose", purpose.name())
                        .param("keyset", sealed.ciphertext())
                        .param("kekUri", sealed.kekUri())
                        .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                        .update()
                == 1;
    }
}
