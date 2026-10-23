package dev.kairos.api.signing.infrastructure;

import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.signing.application.SigningStore;
import dev.kairos.domain.id.TeamId;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.keyset.SealedKeyset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Atomic signing-key replacements and audit entries on the control database. Thread-safe. */
public final class JdbcSigningStore implements SigningStore {
    private static final String SELECT = """
            SELECT team_id, encrypted_keyset, kek_uri, previous_key_valid_until
              FROM scheduler.team_keysets WHERE purpose = 'SIGNING'
            """;
    private static final String REPLACE = """
            UPDATE scheduler.team_keysets
               SET encrypted_keyset = :replacement, kek_uri = :uri, updated_at = :now,
                   previous_key_valid_until = :until
             WHERE team_id = :team AND purpose = 'SIGNING' AND encrypted_keyset = :expected AND kek_uri = :expectedUri
               AND previous_key_valid_until IS NOT DISTINCT FROM :expectedUntil
            """;
    private final JdbcClient control;
    private final ControlTransactions transactions;
    private final AuditWriter writer;

    public JdbcSigningStore(JdbcClient control, ControlTransactions transactions, AuditWriter writer) {
        this.control = control;
        this.transactions = transactions;
        this.writer = writer;
    }

    @Override
    public Optional<Stored> find(TeamId team) {
        return control.sql(SELECT + " AND team_id = :team")
                .param("team", team.value())
                .query((rs, row) -> stored(rs))
                .optional();
    }

    @Override
    public List<Stored> expired(Instant now, int limit) {
        return control.sql(
                        SELECT + " AND previous_key_valid_until <= :now ORDER BY previous_key_valid_until LIMIT :limit")
                .param("now", utc(now))
                .param("limit", limit)
                .query((rs, row) -> stored(rs))
                .list();
    }

    @Override
    public boolean replace(
            Stored expected,
            SealedKeyset replacement,
            @Nullable Instant previousUntil,
            Instant now,
            AuditWriter.Entry audit) {
        return transactions.execute(jdbc -> {
            int changed = control.sql(REPLACE)
                    .param("replacement", replacement.ciphertext())
                    .param("uri", replacement.kekUri())
                    .param("now", utc(now))
                    .param("until", utc(previousUntil), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("team", expected.team().value())
                    .param("expected", expected.sealed().ciphertext())
                    .param("expectedUri", expected.sealed().kekUri())
                    .param("expectedUntil", utc(expected.previousUntil()), Types.TIMESTAMP_WITH_TIMEZONE)
                    .update();
            if (changed == 0) return false;
            writer.record(audit);
            return true;
        });
    }

    private static Stored stored(ResultSet rs) throws SQLException {
        OffsetDateTime until = rs.getObject("previous_key_valid_until", OffsetDateTime.class);
        return new Stored(
                new TeamId(rs.getObject("team_id", UUID.class)),
                new SealedKeyset(rs.getBytes("encrypted_keyset"), rs.getString("kek_uri")),
                until == null ? null : until.toInstant());
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
