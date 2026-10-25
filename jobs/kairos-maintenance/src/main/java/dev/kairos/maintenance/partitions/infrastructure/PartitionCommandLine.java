package dev.kairos.maintenance.partitions.infrastructure;

import dev.kairos.maintenance.commands.application.OperatorCommand;
import dev.kairos.maintenance.commands.application.OperatorResult;
import dev.kairos.maintenance.commands.infrastructure.OperatorCommandLine;
import dev.kairos.maintenance.partitions.application.PartitionStore;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Parses before opening databases, so help and invalid commands need no credentials or connections. */
@Command(
        name = "kairos-maintenance",
        mixinStandardHelpOptions = true,
        description = "Kairos one-shot maintenance commands")
public final class PartitionCommandLine implements Callable<Integer> {
    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 2;
    }

    public static int execute(
            String[] args, Supplier<List<PartitionStore.Result>> action, PrintWriter out, PrintWriter err) {
        return execute(
                args,
                action,
                request -> {
                    throw new IllegalStateException("Operator commands unavailable.");
                },
                () -> false,
                out,
                err);
    }

    public static int execute(
            String[] args,
            Supplier<List<PartitionStore.Result>> action,
            Function<OperatorCommand, OperatorResult> operators,
            BooleanSupplier terminal,
            PrintWriter out,
            PrintWriter err) {
        var command = new CommandLine(new PartitionCommandLine()).addSubcommand("partitions", new Partitions(action));
        OperatorCommandLine.register(command, operators, terminal);
        command.setOut(out).setErr(err);
        command.setParameterExceptionHandler((exception, arguments) -> {
            exception.getCommandLine().getErr().println("Invalid command arguments. Use --help for usage.");
            return 2;
        });
        command.setExecutionExceptionHandler((exception, line, result) -> {
            line.getErr()
                    .println("Maintenance command failed: "
                            + exception.getClass().getSimpleName());
            return exception instanceof IllegalArgumentException ? 2 : 1;
        });
        try {
            return command.execute(args);
        } finally {
            // Console.writer() is buffered: flush the one-time credential before main calls System.exit.
            out.flush();
            err.flush();
        }
    }

    @Command(
            name = "partitions",
            mixinStandardHelpOptions = true,
            description = "Create UTC daily partitions through N+3 and apply 7-day / 90-day retention")
    private static final class Partitions implements Callable<Integer> {
        private final Supplier<List<PartitionStore.Result>> action;

        @Spec
        private CommandSpec spec;

        Partitions(Supplier<List<PartitionStore.Result>> action) {
            this.action = action;
        }

        @Override
        public Integer call() {
            for (var result : action.get())
                spec.commandLine()
                        .getOut()
                        .printf(
                                "%s: %s; created=%d moved=%d dropped=%d purged=%d%n",
                                result.database(),
                                result.skipped() ? "skipped (another partition manager holds the lock)" : "complete",
                                result.created(),
                                result.moved(),
                                result.dropped(),
                                result.purged());
            return 0;
        }
    }
}
