package dev.kairos.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Aggregates fault injection evidence and complete-delivery verification for T-305. */
final class ChaosReport {
    static final List<String> EXPERIMENTS = List.of(
            "executor-kill",
            "watcher-handover",
            "kafka-pause",
            "redis-restart",
            "cell-pause",
            "slow-target",
            "rolling-restart");

    private final BenchFiles files;

    ChaosReport(BenchFiles files) {
        this.files = files;
    }

    int run() throws IOException {
        List<Map<String, Object>> experiments = new ArrayList<>();
        boolean passed = true;
        for (String name : EXPERIMENTS) {
            Map<String, Object> result = inspect(name);
            experiments.add(result);
            passed &= Boolean.TRUE.equals(result.get("passed"));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("task", "T-305");
        report.put("generatedAt", Instant.now().toString());
        report.put("passed", passed);
        report.put("experiments", experiments);
        files.write("chaos.json", report);
        StringBuilder markdown = new StringBuilder("# T-305 resilience and duplicate execution proof\n\n");
        markdown.append("Result: **").append(passed ? "PASS" : "FAIL").append("**\n\n");
        markdown.append(
                "| Experiment | Result | Duplicate effects | Redeliveries | Missing runs | Readiness (s) | Latest delivery (s) | Max fault lag (s) | Limit (s) |\n");
        markdown.append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Map<String, Object> row : experiments) {
            markdown.append("| ")
                    .append(row.get("name"))
                    .append(" | ")
                    .append(Boolean.TRUE.equals(row.get("passed")) ? "PASS" : "FAIL")
                    .append(" | ")
                    .append(row.getOrDefault("duplicates", "—"))
                    .append(" | ")
                    .append(row.getOrDefault("redeliveries", "—"))
                    .append(" | ")
                    .append(row.getOrDefault("missingRuns", "—"))
                    .append(" | ")
                    .append(row.getOrDefault("recoverySeconds", "—"))
                    .append(" | ")
                    .append(row.getOrDefault("deliveryRecoverySeconds", "—"))
                    .append(" | ")
                    .append(row.getOrDefault("faultMaxLagSeconds", "—"))
                    .append(" | ")
                    .append(row.getOrDefault("recoveryLimitSeconds", "—"))
                    .append(" |\n");
        }
        markdown.append("\nReadiness is measured from fault restoration to service health. ")
                .append(
                        "Latest delivery is the latest start of a run due during the fault, measured from restoration. ")
                .append(
                        "The 20-second HLD node target applies to maximum start lag during executor and watcher faults.\n");
        files.writeText("chaos.md", markdown.toString());
        System.out.println("T-305 chaos: " + (passed ? "PASS" : "FAIL") + "; " + files.path("chaos.md"));
        return passed ? 0 : 1;
    }

    private Map<String, Object> inspect(String name) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        var faultPath = files.path(name + "/fault.json");
        var verificationPath = files.path(name + "/verification.json");
        if (!Files.exists(faultPath) || !Files.exists(verificationPath)) {
            row.put("passed", false);
            row.put("reason", "Missing fault or verification evidence");
            return row;
        }
        JsonNode fault = BenchFiles.JSON.readTree(Files.readString(faultPath));
        JsonNode verification = BenchFiles.JSON.readTree(Files.readString(verificationPath));
        Instant started = Instant.parse(BenchApi.required(fault, "startedAt"));
        Instant restored = Instant.parse(BenchApi.required(fault, "restoredAt"));
        Instant healthy = Instant.parse(BenchApi.required(fault, "healthyAt"));
        int limit = recoveryLimit(name);
        long recovery = Math.max(0, Duration.between(restored, healthy).toSeconds());
        boolean validWindow = !started.isAfter(restored) && !restored.isAfter(healthy);
        boolean proved = verification.path("passed").asBoolean()
                && verification.path("chaos").asBoolean()
                && !verification.path("diagnostic").asBoolean()
                && verification.path("profile").asString().equals("smoke")
                && verification.path("duplicates").asLong() == 0
                && verification.path("missingRuns").asLong() == 0
                && verification.path("trackingComplete").asBoolean()
                && verification.path("faultAffectedRuns").asLong() > 0
                && verification.path("deliveryRecoverySeconds").isNumber()
                && verification.path("deliveryRecoverySeconds").asLong() >= 0
                && verification.path("faultMaxLagSeconds").isNumber()
                && verification.path("faultMaxLagSeconds").asLong() >= 0
                && validWindow
                && recovery <= limit;
        if (name.equals("executor-kill")) proved &= fault.path("attempts").asInt() == 5;
        if (name.equals("kafka-pause")) {
            long before = fault.path("outboxBefore").asLong();
            long during = fault.path("outboxDuring").asLong();
            row.put("outboxBefore", before);
            row.put("outboxDuring", during);
            proved &= during > before;
        }
        if (name.equals("redis-restart")) {
            boolean changed = fault.path("replicationIdChanged").asBoolean();
            row.put("replicationIdChanged", changed);
            proved &= changed;
        }
        if (name.equals("cell-pause")) {
            long progress = fault.path("cell1FinalizedDuringFault").asLong();
            row.put("cell1FinalizedDuringFault", progress);
            proved &= progress > 0;
        }
        row.put("startedAt", started.toString());
        row.put("restoredAt", restored.toString());
        row.put("healthyAt", healthy.toString());
        row.put("recoverySeconds", recovery);
        row.put("recoveryLimitSeconds", limit);
        row.put("duplicates", verification.path("duplicates").asLong());
        row.put("redeliveries", verification.path("redeliveries").asLong());
        row.put("missingRuns", verification.path("missingRuns").asLong());
        row.put("scheduleLagP99Ms", verification.path("scheduleLagP99Ms").asDouble());
        row.put("faultAffectedRuns", verification.path("faultAffectedRuns").asLong());
        row.put(
                "deliveryRecoverySeconds",
                verification.path("deliveryRecoverySeconds").asLong());
        row.put("faultMaxLagSeconds", verification.path("faultMaxLagSeconds").asLong());
        if (name.equals("executor-kill") || name.equals("watcher-handover")) {
            proved &= verification.path("faultMaxLagSeconds").asLong() <= limit;
        }
        row.put("passed", proved);
        if (!proved) row.put("reason", "Fault evidence, delivery, or readiness recovery failed");
        return row;
    }

    private static int recoveryLimit(String name) {
        return switch (name) {
            case "executor-kill", "watcher-handover" -> 20;
            case "kafka-pause", "redis-restart", "cell-pause" -> 120;
            case "slow-target", "rolling-restart" -> 300;
            default -> throw new IllegalArgumentException("Unknown experiment: " + name);
        };
    }
}
