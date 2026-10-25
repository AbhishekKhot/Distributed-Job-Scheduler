package dev.kairos.bench;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/** Entry point and shared environment for isolated benchmark commands. */
@CommandLine.Command(
        name = "bench-cli",
        mixinStandardHelpOptions = true,
        description = "Seed, drive and verify Kairos scheduler benchmarks",
        subcommands = {SeedCommand.class, LoadCommand.class, VerifyCommand.class, ReportCommand.class})
final class BenchCommand implements Callable<Integer> {
    @CommandLine.Option(names = "--api-url", description = "Kairos API base URL")
    String apiUrl;

    @CommandLine.Option(names = "--output-dir", description = "Private session and report directory")
    Path outputDir;

    String cell0Url;
    String cell1Url;
    String dbUser;
    String dbPassword;

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 2;
    }

    BenchApi api() {
        return new BenchApi(apiUrl == null ? env("KAIROS_API_URL", "http://kairos-api:8080") : apiUrl);
    }

    BenchFiles files() throws IOException {
        return new BenchFiles(outputDir == null ? Path.of(env("KAIROS_BENCH_OUTPUT_DIR", "build/bench")) : outputDir);
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    String cellUrl(int cell) {
        String override = cell == 0 ? cell0Url : cell1Url;
        return override == null ? requiredEnv("KAIROS_CELL_" + cell + "_DB_URL") : override;
    }

    String dbUser() {
        return dbUser == null ? env("KAIROS_BENCH_DB_USER", "postgres") : dbUser;
    }

    String dbPassword() {
        return dbPassword == null ? requiredEnv("KAIROS_BENCH_DB_PASSWORD") : dbPassword;
    }
}
