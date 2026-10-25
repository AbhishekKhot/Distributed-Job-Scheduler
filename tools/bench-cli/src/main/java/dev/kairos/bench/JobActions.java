package dev.kairos.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import tools.jackson.databind.JsonNode;

/** Runs independent job lifecycle API calls with bounded concurrency. */
final class JobActions {
    private JobActions() {}

    static int change(BenchApi api, List<String> jobs, String apiKey, String action) throws InterruptedException {
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (String job : jobs) {
                futures.add(pool.submit(() -> {
                    for (int attempt = 0; attempt < 3; attempt++) {
                        try {
                            api.post("v1/jobs/" + job + "/" + action, Map.of(), apiKey, false);
                            return;
                        } catch (Exception failure) {
                            try {
                                JsonNode current = api.get("v1/jobs/" + job, apiKey);
                                String desired = action.equals("resume") ? "ACTIVE" : "PAUSED";
                                if (desired.equals(current.path("status").asString())) return;
                            } catch (Exception ignored) {
                                // Retry the original operation after a transient lookup failure.
                            }
                            if (attempt == 2) throw new IllegalStateException(failure);
                            try {
                                Thread.sleep((attempt + 1) * 200L);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                        }
                    }
                }));
            }
            int failures = 0;
            String firstFailure = null;
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException failure) {
                    failures++;
                    if (firstFailure == null) firstFailure = failure.getCause().getMessage();
                }
            }
            if (firstFailure != null) System.err.println(action + " failure: " + firstFailure);
            return failures;
        }
    }
}
