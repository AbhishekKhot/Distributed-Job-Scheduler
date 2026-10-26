package dev.kairos.bench;

import dev.kairos.bench.scenario.ScenarioHttp;
import dev.kairos.bench.scenario.ScenarioRunner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/** Runs one or all end-to-end scenarios against the packaged Compose stack. */
@CommandLine.Command(name = "scenario", mixinStandardHelpOptions = true)
final class ScenarioCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    BenchCommand parent;

    @CommandLine.Parameters(index = "0", description = "Scenario name or 'all'")
    String name;

    @CommandLine.Option(names = "--target-url", defaultValue = "https://mock-target:8443")
    String targetUrl;

    @CommandLine.Option(names = "--management-url", defaultValue = "http://mock-target:8081")
    String managementUrl;

    @Override
    public Integer call() throws Exception {
        if (name.equals("chaos")) return new ChaosReport(parent.files()).run();
        List<String> names = name.equals("all") ? ScenarioRunner.NAMES : List.of(name);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startedAt", Instant.now().toString());
        List<Map<String, Object>> results = new ArrayList<>();
        report.put("results", results);
        try (ScenarioHttp api = new ScenarioHttp(
                        parent.apiUrl == null
                                ? BenchCommand.env("KAIROS_API_URL", "http://kairos-api:8080")
                                : parent.apiUrl);
                ScenarioHttp target = new ScenarioHttp(managementUrl)) {
            ScenarioRunner runner = new ScenarioRunner(api, target, targetUrl);
            for (String scenario : names) {
                try {
                    Map<String, Object> result = runner.run(scenario);
                    results.add(result);
                    System.out.printf("PASS %s (%s ms)%n", scenario, result.get("durationMs"));
                } catch (Exception failure) {
                    results.add(Map.of(
                            "scenario",
                            scenario,
                            "passed",
                            false,
                            "error",
                            failure.getClass().getSimpleName() + ": " + failure.getMessage()));
                    report.put("finishedAt", Instant.now().toString());
                    report.put("passed", false);
                    parent.files().write("scenarios.json", report);
                    throw failure;
                }
            }
        }
        report.put("finishedAt", Instant.now().toString());
        report.put("passed", true);
        parent.files().write("scenarios.json", report);
        return 0;
    }
}
