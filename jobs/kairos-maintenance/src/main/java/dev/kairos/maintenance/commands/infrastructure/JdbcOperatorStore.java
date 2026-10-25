package dev.kairos.maintenance.commands.infrastructure;

import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.maintenance.commands.application.OperatorCommand;
import dev.kairos.maintenance.commands.application.OperatorResult;
import dev.kairos.maintenance.commands.application.OperatorStore;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.SecretValue;
import java.security.SecureRandom;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Parameterized control-only writes. Audit failure rolls back every associated change. */
public final class JdbcOperatorStore implements OperatorStore {
    private final ControlTransactions transactions;
    private final KairosIdFactory ids;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcOperatorStore(ControlTransactions transactions, Clock clock) {
        this.transactions = transactions;
        this.ids = new KairosIdFactory(clock, new SecureRandom());
    }

    @Override
    public OperatorResult setQuota(OperatorCommand.SetQuota command, Instant at) {
        return transactions.execute(jdbc -> {
            var id = jdbc.sql("""
                UPDATE scheduler.teams SET quotas = quotas || CAST(:patch AS jsonb), updated_at = :at
                WHERE slug = :slug RETURNING team_id
                """)
                    .param("patch", json.writeValueAsString(command.quotas()))
                    .param("at", at.atOffset(ZoneOffset.UTC))
                    .param("slug", command.team())
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(() -> new IllegalStateException("Team not found."));
            audit(
                    jdbc,
                    id,
                    "team.set-quota",
                    "TEAM_QUOTA_UPDATED",
                    "team",
                    id,
                    json.writeValueAsString(command.quotas()),
                    at);
            return new OperatorResult(id, 0, null);
        });
    }

    @Override
    public OperatorResult disable(OperatorCommand.DisableUser command, Instant at) {
        return user(command.email(), null, at);
    }

    @Override
    public OperatorResult reset(OperatorCommand.ResetPassword command, SecretValue passwordHash, Instant at) {
        return user(command.email(), passwordHash, at);
    }

    private OperatorResult user(String email, @Nullable SecretValue hash, Instant at) {
        return transactions.execute(jdbc -> {
            // Lock the user before sessions, matching the account password-change ordering.
            var user = jdbc.sql("SELECT user_id, team_id FROM scheduler.users WHERE email=:email FOR UPDATE")
                    .param("email", email)
                    .query((rs, row) ->
                            new User(rs.getObject("user_id", UUID.class), rs.getObject("team_id", UUID.class)))
                    .optional()
                    .orElseThrow(() -> new IllegalStateException("User not found."));
            if (hash == null) {
                jdbc.sql("UPDATE scheduler.users SET status='DISABLED', updated_at=:at WHERE user_id=:id")
                        .param("at", at.atOffset(ZoneOffset.UTC))
                        .param("id", user.id())
                        .update();
            } else {
                jdbc.sql("""
                    UPDATE scheduler.users SET password_hash=:hash, password_changed_at=:at, updated_at=:at,
                    failed_logins=0, locked_until=NULL WHERE user_id=:id
                    """)
                        .param("hash", hash.reveal())
                        .param("at", at.atOffset(ZoneOffset.UTC))
                        .param("id", user.id())
                        .update();
            }
            int revoked = jdbc.sql(
                            "UPDATE scheduler.user_sessions SET status='REVOKED' WHERE user_id=:id AND status<>'REVOKED'")
                    .param("id", user.id())
                    .update();
            audit(
                    jdbc,
                    user.team(),
                    hash == null ? "user.disable" : "user.reset-password",
                    hash == null ? "USER_DISABLED" : "PASSWORD_RESET",
                    "user",
                    user.id(),
                    "{\"sessionsRevoked\":" + revoked + "}",
                    at);
            return new OperatorResult(user.id(), revoked, null);
        });
    }

    private void audit(
            JdbcClient jdbc,
            @Nullable UUID team,
            String command,
            String action,
            String type,
            UUID id,
            String details,
            Instant at) {
        jdbc.sql("""
            INSERT INTO scheduler.audit_log(audit_id,at,team_id,actor,action,resource_type,resource_id,details)
            VALUES (:audit,:at,:team,:actor,:action,:type,:id,CAST(:details AS jsonb))
            """)
                .param("audit", ids.newId())
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("team", team, Types.OTHER)
                .param("actor", "operator:" + command)
                .param("action", action)
                .param("type", type)
                .param("id", id.toString())
                .param("details", details)
                .update();
    }

    private record User(UUID id, @Nullable UUID team) {}
}
