package dev.kairos.bench;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;

/** Re-renders the last verified result without rerunning load or querying services. */
@CommandLine.Command(name = "report", mixinStandardHelpOptions = true)
final class ReportCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    BenchCommand parent;

    @Override
    public Integer call() throws Exception {
        BenchFiles files = parent.files();
        JsonNode report = files.read("verification.json");
        Map<String, Object> values = new LinkedHashMap<>();
        for (var field : report.properties()) values.put(field.getKey(), field.getValue());
        render(files, values);
        System.out.println(files.path("report.md"));
        return report.path("passed").asBoolean() ? 0 : 1;
    }

    static void render(BenchFiles files, Map<String, Object> report) throws Exception {
        String markdown = "# Kairos benchmark: " + value(report, "profile") + "\n\n"
                + (Boolean.parseBoolean(value(report, "diagnostic"))
                        ? "Diagnostic override; not profile acceptance.\n\n"
                        : "")
                + "Result: **" + (Boolean.parseBoolean(value(report, "passed")) ? "PASS" : "FAIL") + "**\n\n"
                + "| Metric | Value |\n|---|---:|\n"
                + row(report, "Achieved executions/s", "achievedExecutionsPerSecond")
                + row(report, "Schedule lag p50 (ms)", "scheduleLagP50Ms")
                + row(report, "Schedule lag p95 (ms)", "scheduleLagP95Ms")
                + row(report, "Schedule lag p99 (ms)", "scheduleLagP99Ms")
                + row(report, "Duplicates", "duplicates")
                + row(report, "Missing runs", "missingRuns")
                + row(report, "Unexpected target keys", "unexpectedTargetKeys")
                + row(report, "DLQ entries", "dlqCount")
                + row(report, "Request failures", "requestFailures")
                + row(report, "Dropped at client", "capacityDropped")
                + row(report, "Signature failures", "signatureFailures")
                + "\nOutcome mix: `" + value(report, "outcomeMix") + "`\n";
        files.writeText("report.md", markdown);
    }

    private static String row(Map<String, Object> report, String label, String key) {
        return "| " + label + " | " + value(report, key) + " |\n";
    }

    private static String value(Map<String, Object> report, String key) {
        return String.valueOf(report.get(key));
    }
}
