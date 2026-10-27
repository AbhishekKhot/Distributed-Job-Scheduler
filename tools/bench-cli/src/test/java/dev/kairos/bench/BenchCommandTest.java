package dev.kairos.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Exercises real CLI parsing, API requests, journal creation and report rendering. */
class BenchCommandTest {
    @TempDir
    Path directory;

    @Test
    void profilesHaveTheSpecifiedRatesAndDuration() {
        assertThat(LoadProfile.parse("smoke").rateAt(Duration.ZERO)).isEqualTo(500);
        assertThat(LoadProfile.parse("smoke").duration()).isEqualTo(Duration.ofMinutes(2));
        assertThat(LoadProfile.parse("steady-20k").rateAt(Duration.ofMinutes(29)))
                .isEqualTo(20_000);
        assertThat(LoadProfile.parse("ramp-50k").rateAt(Duration.ofMinutes(5))).isEqualTo(35_000);
        assertThat(LoadProfile.parse("ramp-50k").rateAt(Duration.ofMinutes(10))).isEqualTo(50_000);
        assertThat(LoadProfile.parse("burst").rateAt(Duration.ZERO)).isEqualTo(50_000);
        assertThatThrownBy(() -> LoadProfile.parse("unknown")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void privateFilesAndVerificationMathAreStable() throws Exception {
        BenchFiles files = new BenchFiles(directory);
        files.write("sample.json", Map.of("count", 3));
        assertThat(files.read("sample.json").path("count").asInt()).isEqualTo(3);
        files.writeText("sample.txt", "ready");
        assertThat(Files.readString(files.path("sample.txt"))).isEqualTo("ready");
        assertThat(VerifyCommand.percentile(List.of(10L, 20L, 30L, 40L), .95)).isEqualTo(40);
        assertThat(VerifyCommand.percentile(List.of(), .95)).isEqualTo(-1);
        assertThat(VerifyCommand.counts(BenchFiles.JSON.readTree("{\"counts\":{\"a\":2,\"b\":1}}")))
                .containsEntry("a", 2L)
                .containsEntry("b", 1L);
        assertThat(VerifyCommand.effectCounts(
                        BenchFiles.JSON.readTree("{\"counts\":{\"a\":2},\"effectCounts\":{\"a\":1}}")))
                .containsEntry("a", 1L);
    }

    @Test
    void seedAndShortApiLoadUseThePublicContracts() throws Exception {
        List<String> paths = java.util.Collections.synchronizedList(new ArrayList<>());
        try (FakeApi api = new FakeApi(paths)) {
            String root = api.url();
            int seed = new CommandLine(new BenchCommand())
                    .execute(
                            "--api-url",
                            root,
                            "--output-dir",
                            directory.toString(),
                            "seed",
                            "--jobs",
                            "3",
                            "--interval-sec",
                            "86400",
                            "--max-retries",
                            "5",
                            "--max-concurrency",
                            "321",
                            "--retry-max-age-sec",
                            "900",
                            "--management-url",
                            root);
            assertThat(seed).isZero();
            BenchFiles files = new BenchFiles(directory);
            assertThat(files.read("session.json").path("reusableJobs").size()).isEqualTo(3);
            assertThat(api.endpointRequests()).singleElement().satisfies(endpoint -> {
                assertThat(endpoint.path("retryPolicy").path("maxRetries").asInt())
                        .isEqualTo(5);
                assertThat(endpoint.path("retryPolicy").path("maxAgeSec").asInt())
                        .isEqualTo(900);
                assertThat(endpoint.path("limits").path("maxConcurrency").asInt())
                        .isEqualTo(321);
            });
            assertThat(paths)
                    .contains(
                            "/v1/auth/signup",
                            "/v1/auth/login",
                            "/v1/teams",
                            "/v1/api-keys",
                            "/ca.pem",
                            "/v1/ca-bundles",
                            "/v1/endpoints",
                            "/v1/jobs:batch");
            int load = new CommandLine(new BenchCommand())
                    .execute(
                            "--api-url",
                            root,
                            "--output-dir",
                            directory.toString(),
                            "load",
                            "--profile",
                            "smoke",
                            "--seconds",
                            "1",
                            "--diagnostic-rate",
                            "5",
                            "--immediate-jobs",
                            "1");
            assertThat(load).isZero();
            assertThat(files.read("load.json").path("driver").asString()).isEqualTo("api");
            assertThat(files.read("load.json").path("diagnostic").asBoolean()).isTrue();
            assertThat(Files.readAllLines(files.path("expected.ndjson"))).hasSizeGreaterThanOrEqualTo(4);
            assertThat(paths).contains("/v1/jobs", "/v1/jobs/job-0/runnow", "/stats/snapshot");
            files.write("verification.json", Map.of("passed", true, "profile", "smoke", "diagnostic", true));
            int report = new CommandLine(new BenchCommand()).execute("--output-dir", directory.toString(), "report");
            assertThat(report).isZero();
            assertThat(Files.readString(files.path("report.md"))).contains("Diagnostic override");
        }
    }

    @Test
    void provisionTeamThenResumeSeedingAfterQuotaChange() throws Exception {
        List<String> paths = java.util.Collections.synchronizedList(new ArrayList<>());
        try (FakeApi api = new FakeApi(paths)) {
            String root = api.url();
            int provision = new CommandLine(new BenchCommand())
                    .execute(
                            "--api-url",
                            root,
                            "--output-dir",
                            directory.toString(),
                            "seed",
                            "--jobs",
                            "0",
                            "--interval-sec",
                            "86400",
                            "--management-url",
                            root);
            assertThat(provision).isZero();
            BenchFiles files = new BenchFiles(directory);
            assertThat(files.read("session.json").path("reusableJobs").size()).isZero();
            assertThat(paths).doesNotContain("/v1/jobs:batch");
            int resume = new CommandLine(new BenchCommand())
                    .execute(
                            "--api-url",
                            root,
                            "--output-dir",
                            directory.toString(),
                            "seed",
                            "--resume",
                            "--jobs",
                            "3",
                            "--interval-sec",
                            "86400");
            assertThat(resume).isZero();
            assertThat(files.read("session.json").path("reusableJobs").size()).isEqualTo(3);
            assertThat(paths).contains("/v1/jobs:batch");
        }
    }

    private static final class FakeApi implements AutoCloseable {
        private final HttpServer server;
        private final List<String> paths;
        private final List<tools.jackson.databind.JsonNode> endpointRequests =
                java.util.Collections.synchronizedList(new ArrayList<>());

        FakeApi(List<String> paths) throws IOException {
            this.paths = paths;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", this::respond);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        List<tools.jackson.databind.JsonNode> endpointRequests() {
            return endpointRequests;
        }

        private void respond(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            if (path.equals("/v1/endpoints")) {
                endpointRequests.add(BenchFiles.JSON.readTree(exchange.getRequestBody()));
            }
            byte[] body;
            int status = 200;
            if (path.equals("/ca.pem")) {
                body = "public-ca".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else {
                Object response = switch (path) {
                    case "/v1/auth/signup" -> Map.of("userId", "user");
                    case "/v1/auth/login" -> Map.of("accessToken", "token");
                    case "/v1/teams" -> Map.of("teamId", "team", "signingKey", "signing");
                    case "/v1/api-keys" -> Map.of("key", "api-key");
                    case "/v1/ca-bundles", "/v1/endpoints" -> Map.of("name", "created");
                    case "/v1/jobs:batch" -> {
                        List<Map<String, Object>> rows = new ArrayList<>();
                        for (int i = 0; i < 3; i++) rows.add(Map.of("index", i, "status", 201, "id", "job-" + i));
                        yield Map.of("results", rows);
                    }
                    case "/v1/jobs" -> Map.of("id", "immediate-1");
                    case "/stats/snapshot" -> {
                        Map<String, Object> stats = new LinkedHashMap<>();
                        stats.put("instanceId", "replica-1");
                        stats.put("trackingComplete", true);
                        yield Map.of("version", 1, "stats", stats, "counts", Map.of());
                    }
                    default -> path.endsWith("/runnow") ? Map.of("runId", "run-1") : Map.of();
                };
                body = BenchFiles.JSON.writeValueAsBytes(response);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
