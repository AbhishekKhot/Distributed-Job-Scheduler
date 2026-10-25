package dev.kairos.maintenance.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.maintenance.commands.application.OperatorCommand;
import dev.kairos.maintenance.commands.application.OperatorResult;
import dev.kairos.maintenance.partitions.infrastructure.PartitionCommandLine;
import dev.kairos.security.SecretValue;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OperatorCommandLineTest {
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private final AtomicReference<OperatorCommand> invoked = new AtomicReference<>();

    private int run(String args, boolean terminal, Function<OperatorCommand, OperatorResult> action) {
        return PartitionCommandLine.execute(
                args.split(" "),
                () -> {
                    throw new AssertionError("Unexpected partition action");
                },
                action,
                () -> terminal,
                new PrintWriter(out, true),
                new PrintWriter(err, true));
    }

    private OperatorResult record(OperatorCommand command) {
        invoked.set(command);
        return new OperatorResult(UUID.randomUUID(), 2, null);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "--help",
                "team --help",
                "user --help",
                "team set-quota --help",
                "user disable --help",
                "user reset-password --help"
            })
    void helpDoesNotOpenConnections(String args) {
        assertThat(run(args, false, request -> {
                    throw new AssertionError("Database opened for help");
                }))
                .isZero();
        assertThat(err.toString()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "team",
                "user",
                "team set-quota",
                "team set-quota --team valid",
                "team set-quota --team bad! --max-jobs 1",
                "team set-quota --team valid --max-jobs 0",
                "team set-quota --team valid --max-jobs -1",
                "team set-quota --team valid --max-jobs 2147483648",
                "team set-quota --team valid --max-jobs 2.5",
                "team set-quota --team valid --unsupported 2",
                "user disable",
                "user reset-password",
                "user disable --email garbage",
                "user reset-password --email a@@example.com",
                "user disable --email secret-sentinel"
            })
    void invalidArgumentsFailBeforeDatabaseAccessWithoutEchoingValues(String args) {
        assertThat(run(args, true, request -> {
                    throw new AssertionError("Database opened for invalid arguments");
                }))
                .isEqualTo(2);
        assertThat(err.toString()).doesNotContain("secret-sentinel");
    }

    @Test
    void quotaPatchBindsAllSixAllowlistedValues() {
        assertThat(run(
                        "team set-quota --team payments --max-jobs 10 --max-exec-per-sec 20 --max-inflight 30 --max-workflow-tasks 40 --backfill-per-sec 50 --redrive-per-sec 60",
                        false,
                        this::record))
                .isZero();
        var command = (OperatorCommand.SetQuota) invoked.get();
        assertThat(command.team()).isEqualTo("payments");
        assertThat(command.quotas())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "maxJobs",
                        10,
                        "maxExecPerSec",
                        20,
                        "maxInflight",
                        30,
                        "maxWorkflowTasks",
                        40,
                        "backfillPerSec",
                        50,
                        "redrivePerSec",
                        60));
        assertThat(out.toString()).contains("quotas updated");
    }

    @Test
    void disableNormalizesEmailAndPrintsSafeResult() {
        assertThat(run("user disable --email Alice@Example.COM", false, this::record))
                .isZero();
        assertThat(invoked.get()).isEqualTo(new OperatorCommand.DisableUser("alice@example.com"));
        assertThat(out.toString()).contains("disabled; sessions revoked=2").doesNotContain("alice");
    }

    @Test
    void resetRequiresTerminalBeforeAnyMutation() {
        assertThat(run("user reset-password --email alice@example.com", false, request -> {
                    throw new AssertionError("Reset without terminal");
                }))
                .isEqualTo(1);
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void resetPrintsGeneratedCredentialOnceOnSuccessfulCommit() {
        String secret = UUID.randomUUID().toString();
        assertThat(run(
                        "user reset-password --email alice@example.com",
                        true,
                        request -> new OperatorResult(UUID.randomUUID(), 3, SecretValue.of(secret))))
                .isZero();
        assertThat(out.toString()).contains("sessions revoked=3", "Temporary password (shown once): " + secret);
        assertThat(out.toString().split(java.util.regex.Pattern.quote(secret), -1))
                .hasSize(2);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void flushesBufferedTerminalWriterBeforeReturningToSystemExit() {
        String secret = UUID.randomUUID().toString();
        int status = PartitionCommandLine.execute(
                new String[] {"user", "reset-password", "--email", "alice@example.com"},
                List::of,
                request -> new OperatorResult(UUID.randomUUID(), 1, SecretValue.of(secret)),
                () -> true,
                new PrintWriter(new java.io.BufferedWriter(out)),
                new PrintWriter(new java.io.BufferedWriter(err)));
        assertThat(status).isZero();
        assertThat(out.toString()).contains("Temporary password (shown once): " + secret);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void failuresNeverExposeExceptionPayloadOrPrintPassword() {
        String secret = UUID.randomUUID().toString();
        assertThat(run("user reset-password --email alice@example.com", true, request -> {
                    throw new IllegalStateException(secret);
                }))
                .isEqualTo(1);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).contains("IllegalStateException").doesNotContain(secret);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "@example.com",
                "a@",
                "a@@example.com",
                "a@example",
                ".a@example.com",
                "a.@example.com",
                "a..b@example.com",
                "a b@example.com",
                "a@example..com",
                "a@-example.com",
                "a@example-.com",
                "a@éxample.com",
                "a\nb@example.com",
                "a;drop@example.com"
            })
    void rejectsInvalidEmailsAtApplicationBoundary(String email) {
        assertThatThrownBy(() -> new OperatorCommand.DisableUser(email)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperatorCommand.ResetPassword(email)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlongEmailPartsSlugsAndUnknownQuotaKeys() {
        for (String email : List.of(
                "a".repeat(65) + "@example.com", "a@" + "b".repeat(64) + ".com", "a@" + "b.".repeat(127) + "com"))
            assertThatThrownBy(() -> new OperatorCommand.ResetPassword(email))
                    .isInstanceOf(IllegalArgumentException.class);
        for (String slug : List.of("ab", "Aaa", "a".repeat(64), "a;select"))
            assertThatThrownBy(() -> new OperatorCommand.SetQuota(slug, Map.of("maxJobs", 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperatorCommand.SetQuota("valid", Map.of("sql", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        var original = new java.util.HashMap<>(Map.of("maxJobs", 1));
        var command = new OperatorCommand.SetQuota("valid", original);
        original.put("maxJobs", 99);
        assertThat(command.quotas()).containsEntry("maxJobs", 1);
    }
}
