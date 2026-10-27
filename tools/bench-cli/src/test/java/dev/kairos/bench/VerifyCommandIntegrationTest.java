package dev.kairos.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real PostgreSQL history plus a management snapshot must agree on every execution key. */
@KairosIntegrationTest(kafka = false, redis = false)
class VerifyCommandIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void usesFirstStartAndSeparatesRedeliveriesFromDuplicateEffects(KairosPostgres postgres) throws Exception {
        for (int cell = 0; cell < 2; cell++) createTables(postgres.cellOwnerDataSource(cell));
        UUID team = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(5);
        Instant scheduled = start.plusMillis(200);
        Instant end = start.plusSeconds(2);
        try (Connection connection = postgres.cellOwnerDataSource(0).getConnection();
                var insert = connection.prepareStatement("INSERT INTO scheduler.run_history "
                        + "(run_id, definition_id, team_id, outcome, final, scheduled_at, started_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, run);
            insert.setObject(2, job);
            insert.setObject(3, team);
            insert.setInt(4, 7);
            insert.setBoolean(5, false);
            insert.setTimestamp(6, Timestamp.from(scheduled));
            insert.setTimestamp(7, Timestamp.from(scheduled.plusMillis(50)));
            insert.executeUpdate();
            insert.setInt(4, 1);
            insert.setBoolean(5, true);
            insert.setTimestamp(7, Timestamp.from(scheduled.plusMillis(150)));
            insert.executeUpdate();
        }
        AtomicLong targetCount = new AtomicLong(1);
        AtomicLong effectCount = new AtomicLong(1);
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/stats/snapshot", exchange -> {
            byte[] response = BenchFiles.JSON.writeValueAsBytes(Map.of(
                    "version", 2,
                    "stats",
                            Map.of(
                                    "instanceId",
                                    "replica-1",
                                    "trackingComplete",
                                    true,
                                    "signatureFailures",
                                    0,
                                    "capacityRejected",
                                    0),
                    "counts", Map.of(run.toString(), targetCount.get()),
                    "effectCounts", Map.of(run.toString(), effectCount.get())));
            exchange.sendResponseHeaders(200, response.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(response);
            }
        });
        target.start();
        try {
            String management = "http://127.0.0.1:" + target.getAddress().getPort();
            BenchFiles files = new BenchFiles(directory);
            files.write(
                    "session.json",
                    Map.of(
                            "teamId",
                            team.toString(),
                            "reusableJobs",
                            List.of(),
                            "cronJobs",
                            List.of(),
                            "intervalSec",
                            1));
            Map<String, Object> load = new java.util.LinkedHashMap<>();
            load.put("profile", "smoke");
            load.put("driver", "api");
            load.put("diagnostic", true);
            load.put("startedAt", start.toString());
            load.put("endedAt", end.toString());
            load.put("offered", 1);
            load.put("accepted", 1);
            load.put("requestFailures", 0);
            load.put("capacityDropped", 0);
            load.put("expectedCronFires", 0);
            load.put("managementUrls", List.of(management));
            files.write("load.json", load);
            files.write(
                    "baseline.json",
                    List.of(Map.of(
                            "version", 2,
                            "stats",
                                    Map.of(
                                            "instanceId",
                                            "replica-1",
                                            "trackingComplete",
                                            true,
                                            "signatureFailures",
                                            0,
                                            "capacityRejected",
                                            0),
                            "counts", Map.of(),
                            "effectCounts", Map.of())));
            files.writeText("expected.ndjson", "RUN " + run + "\n");
            BenchCommand parent = new BenchCommand();
            parent.outputDir = directory;
            parent.cell0Url = postgres.cellJdbcUrl(0);
            parent.cell1Url = postgres.cellJdbcUrl(1);
            parent.dbUser = postgres.ownerUsername();
            parent.dbPassword = postgres.ownerPassword();
            VerifyCommand verify = new VerifyCommand();
            verify.parent = parent;
            verify.waitSeconds = 0;
            assertThat(verify.call()).isZero();
            assertThat(files.read("verification.json").path("passed").asBoolean())
                    .isTrue();
            assertThat(files.read("verification.json").path("scheduleLagP99Ms").asDouble())
                    .isEqualTo(50);
            targetCount.set(2);
            assertThat(verify.call()).isZero();
            assertThat(files.read("verification.json").path("redeliveries").asLong())
                    .isEqualTo(1);
            assertThat(files.read("verification.json").path("duplicates").asLong())
                    .isZero();
            effectCount.set(2);
            assertThat(verify.call()).isEqualTo(1);
            assertThat(files.read("verification.json").path("duplicates").asLong())
                    .isEqualTo(1);
        } finally {
            target.stop(0);
        }
    }

    private static void createTables(DataSource source) throws Exception {
        try (Connection connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS scheduler.run_history ("
                    + "run_id uuid, definition_id uuid, team_id uuid, outcome integer, final boolean, "
                    + "scheduled_at timestamptz, started_at timestamptz)");
            statement.execute("CREATE TABLE IF NOT EXISTS scheduler.jobs ("
                    + "job_id uuid, team_id uuid, created_at timestamptz)");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS scheduler.schedules (" + "schedule_id uuid, next_fire_at timestamptz)");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS scheduler.dead_letters (" + "team_id uuid, scheduled_at timestamptz)");
        }
    }
}
