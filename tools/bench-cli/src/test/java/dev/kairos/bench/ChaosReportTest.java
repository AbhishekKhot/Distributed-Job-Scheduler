package dev.kairos.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ChaosReportTest {
    @TempDir
    Path directory;

    @Test
    void requiresEveryExperimentAndRejectsIncompleteDelivery() throws Exception {
        BenchFiles files = new BenchFiles(directory);
        for (String experiment : ChaosReport.EXPERIMENTS) {
            BenchFiles part = new BenchFiles(directory.resolve(experiment));
            Map<String, Object> fault = new java.util.HashMap<>(Map.of(
                    "startedAt", "2026-10-26T00:00:00Z",
                    "restoredAt", "2026-10-26T00:00:30Z",
                    "healthyAt", "2026-10-26T00:00:40Z",
                    "attempts", experiment.equals("executor-kill") ? 5 : 1));
            fault.put("outboxBefore", 0);
            fault.put("outboxDuring", 5);
            fault.put("replicationIdChanged", true);
            fault.put("cell1FinalizedDuringFault", 10);
            part.write("fault.json", fault);
            Map<String, Object> verification = new java.util.HashMap<>(Map.of(
                    "passed",
                    true,
                    "chaos",
                    true,
                    "diagnostic",
                    false,
                    "profile",
                    "smoke",
                    "duplicates",
                    0,
                    "missingRuns",
                    0,
                    "trackingComplete",
                    true,
                    "faultAffectedRuns",
                    50,
                    "deliveryRecoverySeconds",
                    5,
                    "scheduleLagP99Ms",
                    40_000));
            verification.put("faultMaxLagSeconds", 5);
            verification.put("redeliveries", experiment.equals("executor-kill") ? 3 : 0);
            part.write("verification.json", verification);
        }
        assertThat(new CommandLine(new BenchCommand())
                        .execute("--output-dir", directory.toString(), "scenario", "chaos"))
                .isZero();
        assertThat(files.read("chaos.json").path("passed").asBoolean()).isTrue();
        assertThat(files.read("chaos.json").path("experiments").size()).isEqualTo(7);
        assertThat(files.read("chaos.json")
                        .path("experiments")
                        .get(0)
                        .path("redeliveries")
                        .asLong())
                .isEqualTo(3);

        new BenchFiles(directory.resolve("cell-pause"))
                .write(
                        "verification.json",
                        Map.of(
                                "passed", false,
                                "chaos", true,
                                "diagnostic", false,
                                "profile", "smoke",
                                "duplicates", 1,
                                "missingRuns", 0,
                                "trackingComplete", true));
        assertThat(new CommandLine(new BenchCommand())
                        .execute("--output-dir", directory.toString(), "scenario", "chaos"))
                .isEqualTo(1);
        assertThat(files.read("chaos.json")
                        .path("experiments")
                        .get(4)
                        .path("duplicates")
                        .asLong())
                .isEqualTo(1);
    }
}
