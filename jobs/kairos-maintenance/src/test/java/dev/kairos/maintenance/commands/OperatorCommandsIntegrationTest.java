package dev.kairos.maintenance.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.maintenance.commands.application.OperatorCommand;
import dev.kairos.maintenance.commands.application.OperatorService;
import dev.kairos.maintenance.commands.infrastructure.JdbcOperatorStore;
import dev.kairos.maintenance.partitions.PartitionFixtures;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.password.Argon2idParameters;
import dev.kairos.security.password.PasswordHasher;
import dev.kairos.security.password.PasswordPolicy;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@KairosIntegrationTest(postgres = true)
class OperatorCommandsIntegrationTest {
    static final PasswordHasher HASHER = new PasswordHasher(Argon2idParameters.DEFAULT, new SecureRandom());
    final MutableClock clock = MutableClock.at(Instant.parse("2026-10-25T12:00:00Z"));
    Database app, owner;
    OperatorService service;
    UUID team, user, otherUser;
    String slug, email, oldPassword;

    @BeforeEach
    void prepare(KairosPostgres postgres) {
        PartitionFixtures.ensureSchema(postgres);
        var meters = new SimpleMeterRegistry();
        app = Database.of(Database.CONTROL, postgres.controlDataSource(), DatabaseOptions.defaults(), meters);
        owner = Database.of(Database.CONTROL, postgres.controlOwnerDataSource(), DatabaseOptions.defaults(), meters);
        service = new OperatorService(
                new JdbcOperatorStore(new ControlTransactions(app), clock),
                HASHER,
                PasswordPolicy.withBundledList(),
                new SecureRandom(),
                clock);
        team = UUID.randomUUID();
        user = UUID.randomUUID();
        otherUser = UUID.randomUUID();
        slug = "team-" + team;
        email = user + "@example.com";
        oldPassword = UUID.randomUUID().toString();
        app.jdbc()
                .sql(
                        "INSERT INTO scheduler.teams(team_id,slug,quotas,created_at,updated_at) VALUES (:id,:slug,'{\"maxJobs\":10,\"maxExecPerSec\":20,\"custom\":123}',now(),now())")
                .param("id", team)
                .param("slug", slug)
                .update();
        for (UUID id : java.util.List.of(user, otherUser)) {
            app.jdbc()
                    .sql(
                            "INSERT INTO scheduler.users(user_id,team_id,email,password_hash,password_changed_at,created_at,updated_at) VALUES (:id,:team,:email,:hash,now(),now(),now())")
                    .param("id", id)
                    .param("team", team)
                    .param("email", id + "@example.com")
                    .param("hash", HASHER.hash(oldPassword))
                    .update();
            for (String status : java.util.List.of("ACTIVE", "ROTATED", "REVOKED")) {
                app.jdbc()
                        .sql(
                                "INSERT INTO scheduler.user_sessions(session_id,user_id,family_id,token_hash,status,created_at,expires_at) VALUES (:id,:user,:family,:hash,:status,now(),now()+interval '30 days')")
                        .param("id", UUID.randomUUID())
                        .param("user", id)
                        .param("family", UUID.randomUUID())
                        .param("hash", digest(status + id))
                        .param("status", status)
                        .update();
            }
        }
    }

    @Test
    void quotaPatchPreservesUnspecifiedQuotasAndRecordsTeamScopedAudit() {
        var result = service.execute(new OperatorCommand.SetQuota(slug, Map.of("maxJobs", 42, "maxInflight", 30)));
        assertThat(result.resourceId()).isEqualTo(team);
        assertThat(app.jdbc()
                        .sql("SELECT quotas->>'maxJobs' FROM scheduler.teams WHERE team_id=:id")
                        .param("id", team)
                        .query(String.class)
                        .single())
                .isEqualTo("42");
        assertThat(app.jdbc()
                        .sql("SELECT quotas->>'maxExecPerSec' FROM scheduler.teams WHERE team_id=:id")
                        .param("id", team)
                        .query(String.class)
                        .single())
                .isEqualTo("20");
        assertThat(app.jdbc()
                        .sql("SELECT quotas->>'custom' FROM scheduler.teams WHERE team_id=:id")
                        .param("id", team)
                        .query(String.class)
                        .single())
                .isEqualTo("123");
        assertAudit("operator:team.set-quota", "TEAM_QUOTA_UPDATED", team);
    }

    @Test
    void disableRevokesAllExistingSessionStatesAndPreservesOtherUsers() {
        var result = service.execute(new OperatorCommand.DisableUser(email.toUpperCase(java.util.Locale.ROOT)));
        assertThat(result.sessionsRevoked()).isEqualTo(2);
        assertThat(userValue("status")).isEqualTo("DISABLED");
        assertThat(nonRevoked(user)).isZero();
        assertThat(nonRevoked(otherUser)).isEqualTo(2);
        assertAudit("operator:user.disable", "USER_DISABLED", user);
        assertThat(service.execute(new OperatorCommand.DisableUser(email)).sessionsRevoked())
                .isZero();
        assertThat(audits(user)).isEqualTo(2);
    }

    @Test
    void resetUsesArgon2ClearsLockoutRevokesSessionsAndShowsOnlyRedactedResult() {
        app.jdbc()
                .sql(
                        "UPDATE scheduler.users SET failed_logins=10,locked_until=now()+interval '1 hour' WHERE user_id=:id")
                .param("id", user)
                .update();
        var result = service.execute(new OperatorCommand.ResetPassword(email));
        String password =
                java.util.Objects.requireNonNull(result.temporaryPassword()).reveal();
        assertThat(password).hasSize(32).matches("[A-Za-z0-9_-]+");
        assertThat(PasswordPolicy.withBundledList().violation(password, email)).isEmpty();
        String hash = userValue("password_hash");
        assertThat(HASHER.verify(password, hash)).isTrue();
        assertThat(HASHER.verify(oldPassword, hash)).isFalse();
        assertThat(userValue("status")).isEqualTo("ACTIVE");
        assertThat(userValue("failed_logins")).isEqualTo("0");
        assertThat(userValue("locked_until")).isNull();
        assertThat(userValue("password_changed_at")).startsWith("2026-10-25 12:00:00");
        assertThat(nonRevoked(user)).isZero();
        assertThat(nonRevoked(otherUser)).isEqualTo(2);
        assertThat(result.sessionsRevoked()).isEqualTo(2);
        assertThat(result.toString()).doesNotContain(password, hash);
        assertAudit("operator:user.reset-password", "PASSWORD_RESET", user);
        assertThat(app.jdbc()
                        .sql("SELECT details::text FROM scheduler.audit_log WHERE resource_id=:id")
                        .param("id", user.toString())
                        .query(String.class)
                        .single())
                .contains("sessionsRevoked")
                .doesNotContain(password, hash, email);
        var second = service.execute(new OperatorCommand.ResetPassword(email));
        assertThat(second.temporaryPassword()).isNotEqualTo(result.temporaryPassword());
    }

    @Test
    void resetKeepsDisabledUsersDisabledAndSupportsUsersWithoutTeam() {
        app.jdbc()
                .sql("UPDATE scheduler.users SET status='DISABLED',team_id=NULL WHERE user_id=:id")
                .param("id", user)
                .update();
        service.execute(new OperatorCommand.ResetPassword(email));
        assertThat(userValue("status")).isEqualTo("DISABLED");
        assertThat(app.jdbc()
                        .sql("SELECT team_id IS NULL FROM scheduler.audit_log WHERE resource_id=:id")
                        .param("id", user.toString())
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void unknownTargetsDoNotCreateAuditsOrMutateOtherRows() {
        assertThatThrownBy(() -> service.execute(
                        new OperatorCommand.SetQuota("missing-" + UUID.randomUUID(), Map.of("maxJobs", 1))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.execute(new OperatorCommand.DisableUser(UUID.randomUUID() + "@example.com")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.execute(new OperatorCommand.ResetPassword(UUID.randomUUID() + "@example.com")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(audits(user)).isZero();
        assertThat(userValue("status")).isEqualTo("ACTIVE");
    }

    @Test
    void auditFailureRollsBackPasswordDisableQuotasAndSessionRevocations() {
        String constraint = "reject_operator_" + UUID.randomUUID().toString().replace("-", "");
        owner.jdbc()
                .sql("ALTER TABLE scheduler.audit_log ADD CONSTRAINT " + constraint
                        + " CHECK (actor NOT LIKE 'operator:%') NOT VALID")
                .update();
        String hash = userValue("password_hash");
        try {
            assertThatThrownBy(() -> service.execute(new OperatorCommand.ResetPassword(email)))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> service.execute(new OperatorCommand.DisableUser(email)))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> service.execute(new OperatorCommand.SetQuota(slug, Map.of("maxJobs", 77))))
                    .isInstanceOf(RuntimeException.class);
            assertThat(userValue("password_hash")).isEqualTo(hash);
            assertThat(userValue("status")).isEqualTo("ACTIVE");
            assertThat(nonRevoked(user)).isEqualTo(2);
            assertThat(audits(user)).isZero();
            assertThat(app.jdbc()
                            .sql("SELECT quotas->>'maxJobs' FROM scheduler.teams WHERE team_id=:id")
                            .param("id", team)
                            .query(String.class)
                            .single())
                    .isEqualTo("10");
        } finally {
            owner.jdbc()
                    .sql("ALTER TABLE scheduler.audit_log DROP CONSTRAINT " + constraint)
                    .update();
        }
    }

    @Test
    void concurrentQuotaPatchesDoNotLoseEachOthersChanges() throws Exception {
        try (var tasks = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = tasks.submit(() -> service.execute(new OperatorCommand.SetQuota(slug, Map.of("maxJobs", 55))));
            var second = tasks.submit(
                    () -> service.execute(new OperatorCommand.SetQuota(slug, Map.of("maxExecPerSec", 66))));
            first.get();
            second.get();
        }
        assertThat(app.jdbc()
                        .sql(
                                "SELECT quotas->>'maxJobs'||':'||(quotas->>'maxExecPerSec') FROM scheduler.teams WHERE team_id=:id")
                        .param("id", team)
                        .query(String.class)
                        .single())
                .isEqualTo("55:66");
        assertThat(audits(team)).isEqualTo(2);
    }

    @Test
    void concurrentDisableAndResetCannotReactivateUserOrMissExistingSessions() throws Exception {
        try (var tasks = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var disabled = tasks.submit(() -> service.execute(new OperatorCommand.DisableUser(email)));
            var reset = tasks.submit(() -> service.execute(new OperatorCommand.ResetPassword(email)));
            assertThat(disabled.get().sessionsRevoked() + reset.get().sessionsRevoked())
                    .isEqualTo(2);
        }
        assertThat(userValue("status")).isEqualTo("DISABLED");
        assertThat(nonRevoked(user)).isZero();
        assertThat(audits(user)).isEqualTo(2);
    }

    @Test
    void applicationCredentialsCannotPerformDdl() {
        assertThatThrownBy(() -> app.jdbc()
                        .sql("CREATE TABLE scheduler.operator_forbidden(id integer)")
                        .update())
                .isInstanceOf(RuntimeException.class);
    }

    private void assertAudit(String actor, String action, UUID resource) {
        assertThat(app.jdbc()
                        .sql(
                                "SELECT actor||':'||action FROM scheduler.audit_log WHERE resource_id=:id ORDER BY at,audit_id LIMIT 1")
                        .param("id", resource.toString())
                        .query(String.class)
                        .single())
                .isEqualTo(actor + ":" + action);
        assertThat(app.jdbc()
                        .sql(
                                "SELECT audit_id FROM scheduler.audit_log WHERE resource_id=:id ORDER BY at,audit_id LIMIT 1")
                        .param("id", resource.toString())
                        .query(UUID.class)
                        .single()
                        .version())
                .isEqualTo(7);
    }

    private String userValue(String column) {
        return app.jdbc()
                .sql("SELECT " + column + "::text FROM scheduler.users WHERE user_id=:id")
                .param("id", user)
                .query((rs, row) -> rs.getString(1))
                .list()
                .getFirst();
    }

    private long nonRevoked(UUID id) {
        return app.jdbc()
                .sql("SELECT count(*) FROM scheduler.user_sessions WHERE user_id=:id AND status<>'REVOKED'")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private long audits(UUID id) {
        return app.jdbc()
                .sql("SELECT count(*) FROM scheduler.audit_log WHERE resource_id=:id")
                .param("id", id.toString())
                .query(Long.class)
                .single();
    }

    private static byte[] digest(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
