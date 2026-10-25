package dev.kairos.maintenance.commands.infrastructure;

import dev.kairos.maintenance.commands.application.OperatorCommand;
import dev.kairos.maintenance.commands.application.OperatorResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Command-pattern adapters; validate intent and terminal availability before invoking the context factory. */
public final class OperatorCommandLine {
    private OperatorCommandLine() {}

    public static void register(
            CommandLine root, Function<OperatorCommand, OperatorResult> action, BooleanSupplier terminal) {
        root.addSubcommand("team", new CommandLine(new Group()).addSubcommand("set-quota", new SetQuota(action)));
        root.addSubcommand(
                "user",
                new CommandLine(new Group())
                        .addSubcommand("disable", new Disable(action))
                        .addSubcommand("reset-password", new Reset(action, terminal)));
    }

    @Command(mixinStandardHelpOptions = true)
    private static final class Group implements Callable<Integer> {
        @Spec
        private CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(spec.commandLine().getOut());
            return 2;
        }
    }

    @Command(
            name = "set-quota",
            mixinStandardHelpOptions = true,
            description = "Patch positive team quotas; unspecified quotas are preserved")
    private static final class SetQuota implements Callable<Integer> {
        private final Function<OperatorCommand, OperatorResult> action;

        @Spec
        private CommandSpec spec;

        @Option(names = "--team", required = true)
        private String team;

        @Option(names = "--max-jobs")
        private Integer maxJobs;

        @Option(names = "--max-exec-per-sec")
        private Integer maxExecPerSec;

        @Option(names = "--max-inflight")
        private Integer maxInflight;

        @Option(names = "--max-workflow-tasks")
        private Integer maxWorkflowTasks;

        @Option(names = "--backfill-per-sec")
        private Integer backfillPerSec;

        @Option(names = "--redrive-per-sec")
        private Integer redrivePerSec;

        SetQuota(Function<OperatorCommand, OperatorResult> action) {
            this.action = action;
        }

        @Override
        public Integer call() {
            Map<String, Integer> quotas = new LinkedHashMap<>();
            add(quotas, "maxJobs", maxJobs);
            add(quotas, "maxExecPerSec", maxExecPerSec);
            add(quotas, "maxInflight", maxInflight);
            add(quotas, "maxWorkflowTasks", maxWorkflowTasks);
            add(quotas, "backfillPerSec", backfillPerSec);
            add(quotas, "redrivePerSec", redrivePerSec);
            var result = action.apply(new OperatorCommand.SetQuota(team, quotas));
            spec.commandLine().getOut().printf("Team %s: quotas updated.%n", result.resourceId());
            return 0;
        }

        private static void add(Map<String, Integer> target, String key, Integer value) {
            if (value != null) target.put(key, value);
        }
    }

    @Command(name = "disable", mixinStandardHelpOptions = true, description = "Disable a user and revoke all sessions")
    private static final class Disable implements Callable<Integer> {
        private final Function<OperatorCommand, OperatorResult> action;

        @Spec
        private CommandSpec spec;

        @Option(names = "--email", required = true)
        private String email;

        Disable(Function<OperatorCommand, OperatorResult> action) {
            this.action = action;
        }

        @Override
        public Integer call() {
            var result = action.apply(new OperatorCommand.DisableUser(email));
            spec.commandLine()
                    .getOut()
                    .printf("User %s: disabled; sessions revoked=%d.%n", result.resourceId(), result.sessionsRevoked());
            return 0;
        }
    }

    @Command(
            name = "reset-password",
            mixinStandardHelpOptions = true,
            description =
                    "Reset password, clear login lockout and revoke all sessions; requires an interactive terminal")
    private static final class Reset implements Callable<Integer> {
        private final Function<OperatorCommand, OperatorResult> action;
        private final BooleanSupplier terminal;

        @Spec
        private CommandSpec spec;

        @Option(names = "--email", required = true)
        private String email;

        Reset(Function<OperatorCommand, OperatorResult> action, BooleanSupplier terminal) {
            this.action = action;
            this.terminal = terminal;
        }

        @Override
        public Integer call() {
            var request = new OperatorCommand.ResetPassword(email);
            if (!terminal.getAsBoolean()) {
                spec.commandLine()
                        .getErr()
                        .println("Password reset requires an interactive terminal (-it); no changes made.");
                return 1;
            }
            var result = action.apply(request);
            spec.commandLine()
                    .getOut()
                    .printf(
                            "User %s: password reset; sessions revoked=%d.%n",
                            result.resourceId(), result.sessionsRevoked());
            var password = java.util.Objects.requireNonNull(result.temporaryPassword());
            spec.commandLine().getOut().printf("Temporary password (shown once): %s%n", password.reveal());
            return 0;
        }
    }
}
