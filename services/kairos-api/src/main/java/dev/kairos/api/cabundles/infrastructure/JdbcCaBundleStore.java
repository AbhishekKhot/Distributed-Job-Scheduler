package dev.kairos.api.cabundles.infrastructure;

import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.cabundles.application.CaBundleStore;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.NotFoundException;
import dev.kairos.domain.id.TeamId;
import dev.kairos.persistence.tx.ControlTransactions;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reference checks and bundle mutations use the team's row lock and commit with their audit entry. */
public final class JdbcCaBundleStore implements CaBundleStore {
    private final JdbcClient control;
    private final ControlTransactions transactions;
    private final AuditWriter audit;

    public JdbcCaBundleStore(JdbcClient control, ControlTransactions transactions, AuditWriter audit) {
        this.control = control;
        this.transactions = transactions;
        this.audit = audit;
    }

    @Override
    public Optional<Metadata> find(TeamId team, String name) {
        return control.sql(
                        "SELECT name, created_at FROM scheduler.team_ca_bundles WHERE team_id = :team AND name = :name")
                .param("team", team.value())
                .param("name", name)
                .query((rs, row) -> new Metadata(
                        rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public Metadata create(TeamId team, String actor, String name, String pem, Instant now) {
        return transactions.execute(jdbc -> {
            lock(team);
            if (find(team, name).isPresent())
                throw new ConflictException(ErrorCode.CA_BUNDLE_EXISTS, "A CA bundle with this name already exists.");
            control.sql(
                            "INSERT INTO scheduler.team_ca_bundles (team_id, name, pem, created_at) VALUES (:team, :name, :pem, :now)")
                    .param("team", team.value())
                    .param("name", name)
                    .param("pem", pem)
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .update();
            record(team, actor, "CA_BUNDLE_CREATED", name);
            return new Metadata(name, now);
        });
    }

    @Override
    public List<Metadata> list(TeamId team, @Nullable String after, int limit) {
        String sql = "SELECT name, created_at FROM scheduler.team_ca_bundles WHERE team_id = :team"
                + (after == null ? "" : " AND name > :after") + " ORDER BY name LIMIT :limit";
        var query = control.sql(sql).param("team", team.value()).param("limit", limit);
        if (after != null) query = query.param("after", after);
        return query.query((rs, row) -> new Metadata(
                        rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public void delete(TeamId team, String actor, String name) {
        transactions.execute(jdbc -> {
            lock(team);
            if (find(team, name).isEmpty()) throw new NotFoundException("The CA bundle does not exist.");
            Optional<String> endpoint = control.sql("""
                    SELECT endpoint.name FROM scheduler.api_endpoints endpoint
                     WHERE endpoint.team_id = :team
                       AND (endpoint.spec ->> 'caBundle' = :name OR EXISTS (
                           SELECT 1 FROM scheduler.api_endpoint_versions version
                            WHERE version.endpoint_id = endpoint.endpoint_id
                              AND version.spec ->> 'caBundle' = :name))
                     ORDER BY endpoint.name LIMIT 1
                    """)
                    .param("team", team.value())
                    .param("name", name)
                    .query(String.class)
                    .optional();
            if (endpoint.isPresent())
                throw new ConflictException(
                        ErrorCode.CA_BUNDLE_IN_USE, "The CA bundle is used by endpoint " + endpoint.get() + ".");
            control.sql("DELETE FROM scheduler.team_ca_bundles WHERE team_id = :team AND name = :name")
                    .param("team", team.value())
                    .param("name", name)
                    .update();
            record(team, actor, "CA_BUNDLE_DELETED", name);
            return null;
        });
    }

    private void lock(TeamId team) {
        control.sql("SELECT team_id FROM scheduler.teams WHERE team_id = :team FOR UPDATE")
                .param("team", team.value())
                .query(java.util.UUID.class)
                .single();
    }

    private void record(TeamId team, String actor, String action, String name) {
        audit.record(new AuditWriter.Entry(team, actor, action, "ca-bundle", name, Map.of()));
    }
}
