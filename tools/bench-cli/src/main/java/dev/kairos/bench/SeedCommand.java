package dev.kairos.bench;

import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;

/** Creates a dedicated team, credentials, trust anchor, endpoint and scheduled jobs. */
@CommandLine.Command(name = "seed", mixinStandardHelpOptions = true)
final class SeedCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    BenchCommand parent;

    @CommandLine.Option(names = "--jobs", defaultValue = "500", description = "Number of reusable jobs")
    int jobs;

    @CommandLine.Option(names = "--resume", description = "Add jobs to an existing provisioned session")
    boolean resume;

    @CommandLine.Option(names = "--interval-sec", defaultValue = "1", description = "Reusable job interval")
    int intervalSec;

    @CommandLine.Option(names = "--cron-percent", defaultValue = "0", description = "Percent of cron jobs")
    int cronPercent;

    @CommandLine.Option(names = "--cron-expression", defaultValue = "* * * * *")
    String cronExpression;

    @CommandLine.Option(names = "--burst-at", description = "Future UTC minute for a one-time cron fan-out")
    Instant burstAt;

    @CommandLine.Option(names = "--target-url", defaultValue = "https://mock-target:8443/behave/healthy")
    String targetUrl;

    @CommandLine.Option(names = "--management-url", defaultValue = "http://mock-target:8081")
    String managementUrl;

    @Override
    public Integer call() throws Exception {
        if (jobs < 0 || cronPercent < 0 || cronPercent > 100 || intervalSec < 1 || intervalSec > 31_622_400) {
            throw new IllegalArgumentException("Invalid job count, cron percent or interval");
        }
        if (jobs == 50_000 && cronPercent == 100 && burstAt == null) {
            throw new IllegalArgumentException("--burst-at is required for the 50K cron profile");
        }
        if (burstAt != null) {
            if (!burstAt.equals(burstAt.truncatedTo(ChronoUnit.MINUTES))
                    || !burstAt.isAfter(Instant.now().plusSeconds(60))) {
                throw new IllegalArgumentException("--burst-at must be a UTC minute at least one minute ahead");
            }
            var utc = burstAt.atZone(java.time.ZoneOffset.UTC);
            cronExpression = utc.getMinute() + " " + utc.getHour() + " " + utc.getDayOfMonth() + " "
                    + utc.getMonthValue() + " *";
        }
        BenchFiles files = parent.files();
        if (resume != Files.exists(files.path("session.json")))
            throw new IllegalStateException(
                    resume
                            ? "No session to resume in --output-dir"
                            : "Session already exists; use --resume or a new --output-dir");
        if (resume) return resumeSeed(files);
        byte[] random = new byte[15];
        new SecureRandom().nextBytes(random);
        String suffix = java.util.HexFormat.of().formatHex(random, 0, 4);
        String email = "bench-" + suffix + "@kairos.test";
        String password = "A1!" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String teamName = "bench-" + suffix;
        BenchApi api = parent.api();
        api.post("v1/auth/signup", Map.of("email", email, "password", password), null, false);
        JsonNode login = api.post("v1/auth/login", Map.of("email", email, "password", password), null, false);
        JsonNode team = api.post("v1/teams", Map.of("name", teamName), BenchApi.required(login, "accessToken"), false);
        login = api.post("v1/auth/login", Map.of("email", email, "password", password), null, false);
        String accessToken = BenchApi.required(login, "accessToken");
        JsonNode key = api.post(
                "v1/api-keys",
                Map.of(
                        "name",
                        "bench-" + suffix,
                        "expiresAt",
                        Instant.now().plus(7, ChronoUnit.DAYS).toString()),
                accessToken,
                false);
        String apiKey = BenchApi.required(key, "key");
        String ca = new BenchApi(managementUrl).text("ca.pem");
        String caName = "bench-ca-" + suffix;
        api.post("v1/ca-bundles", Map.of("name", caName, "pem", ca), apiKey, false);
        String endpoint = "bench-target-" + suffix;
        Map<String, Object> endpointBody = new LinkedHashMap<>();
        endpointBody.put("name", endpoint);
        endpointBody.put("method", "POST");
        endpointBody.put("urlTemplate", targetUrl);
        endpointBody.put("caBundle", caName);
        endpointBody.put("timeoutMs", 10_000);
        endpointBody.put(
                "retryPolicy",
                Map.of(
                        "maxRetries", 0,
                        "strategy", "FIXED",
                        "baseDelaySec", 1,
                        "maxDelaySec", 1,
                        "maxAgeSec", 60));
        endpointBody.put("limits", Map.of("maxConcurrency", 10_000, "ratePerSec", 0));
        api.post("v1/endpoints", endpointBody, apiKey, false);
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("createdAt", Instant.now().toString());
        session.put("email", email);
        session.put("password", password);
        session.put("teamId", BenchApi.required(team, "teamId"));
        session.put("apiKey", apiKey);
        session.put("signingKey", BenchApi.required(team, "signingKey"));
        session.put("endpoint", endpoint);
        session.put("targetUrl", targetUrl);
        session.put("managementUrls", List.of(managementUrl));
        session.put("reusableJobs", new ArrayList<String>());
        session.put("cronJobs", new ArrayList<String>());
        session.put("cronExpression", cronExpression);
        session.put("cronPercent", cronPercent);
        session.put("targetJobs", jobs);
        if (burstAt != null) session.put("burstAt", burstAt.toString());
        session.put("intervalSec", intervalSec);
        files.write("session.json", session);
        return createJobs(files, api, session, suffix, apiKey, endpoint);
    }

    private int resumeSeed(BenchFiles files) throws Exception {
        JsonNode previous = files.read("session.json");
        int existing =
                previous.path("reusableJobs").size() + previous.path("cronJobs").size();
        int originalTarget = previous.path("targetJobs").asInt();
        if (originalTarget > 0 && originalTarget != jobs)
            throw new IllegalArgumentException("Resume with the original --jobs total");
        boolean newBurst = existing == 0 && burstAt != null;
        if (previous.path("intervalSec").asInt() != intervalSec
                || previous.path("cronPercent").asInt() != cronPercent
                || (!newBurst && !previous.path("cronExpression").asString().equals(cronExpression))
                || (!newBurst
                        && burstAt != null
                        && !previous.path("burstAt").asString().equals(burstAt.toString()))) {
            throw new IllegalArgumentException("Resume with the original schedule options");
        }
        Map<String, Object> session = new LinkedHashMap<>();
        for (String field : List.of(
                "createdAt",
                "email",
                "password",
                "teamId",
                "apiKey",
                "signingKey",
                "endpoint",
                "targetUrl",
                "cronExpression")) session.put(field, BenchApi.required(previous, field));
        if (previous.has("burstAt")) session.put("burstAt", BenchApi.required(previous, "burstAt"));
        if (newBurst) {
            session.put("burstAt", burstAt.toString());
            session.put("cronExpression", cronExpression);
        }
        session.put("managementUrls", LoadCommand.strings(previous.path("managementUrls")));
        session.put("reusableJobs", new ArrayList<>(LoadCommand.strings(previous.path("reusableJobs"))));
        session.put("cronJobs", new ArrayList<>(LoadCommand.strings(previous.path("cronJobs"))));
        session.put("cronPercent", cronPercent);
        session.put("targetJobs", jobs);
        session.put("intervalSec", intervalSec);
        String email = BenchApi.required(previous, "email");
        String suffix = email.substring("bench-".length(), email.indexOf('@'));
        return createJobs(
                files,
                parent.api(),
                session,
                suffix,
                BenchApi.required(previous, "apiKey"),
                BenchApi.required(previous, "endpoint"));
    }

    @SuppressWarnings("unchecked")
    private int createJobs(
            BenchFiles files, BenchApi api, Map<String, Object> session, String suffix, String apiKey, String endpoint)
            throws Exception {
        List<String> reusable = (List<String>) session.get("reusableJobs");
        List<String> cron = (List<String>) session.get("cronJobs");
        int existing = reusable.size() + cron.size();
        if (jobs < existing) throw new IllegalArgumentException("--jobs cannot be less than already seeded jobs");
        if (resume
                && intervalSec == 1
                && !reusable.isEmpty()
                && JobActions.change(api, reusable, apiKey, "pause") > 0) {
            throw new IllegalStateException("Existing seeded jobs could not be paused");
        }
        int batchSize = intervalSec == 1 ? 100 : 1_000;
        for (int start = existing; start < jobs; start += batchSize) {
            List<Map<String, Object>> batch = new ArrayList<>();
            List<String> batchReusable = new ArrayList<>();
            int end = Math.min(jobs, start + batchSize);
            for (int index = start; index < end; index++) {
                boolean isCron = (long) index * 100 < (long) jobs * cronPercent;
                Map<String, Object> schedule = isCron
                        ? Map.of("type", "CRON", "expression", cronExpression, "timezone", "UTC")
                        : Map.of("type", "INTERVAL", "everySec", intervalSec);
                batch.add(BenchApi.job("bench-" + suffix + "-" + index, endpoint, schedule));
            }
            JsonNode result = api.post("v1/jobs:batch", Map.of("jobs", batch), apiKey, true);
            JsonNode rows = result.path("results");
            if (!rows.isArray() || rows.size() != batch.size())
                throw new IllegalStateException("Incomplete batch result");
            for (int index = 0; index < rows.size(); index++) {
                JsonNode row = rows.get(index);
                if (row.path("status").asInt() != 201) {
                    throw new IllegalStateException("Job batch item " + (start + index) + " returned "
                            + row.path("status").asInt());
                }
                String id = BenchApi.required(row, "id");
                boolean isCron = (long) (start + index) * 100 < (long) jobs * cronPercent;
                (isCron ? cron : reusable).add(id);
                if (!isCron) batchReusable.add(id);
            }
            files.write("session.json", session);
            if (intervalSec == 1) {
                int failed = JobActions.change(api, batchReusable, apiKey, "pause");
                if (failed > 0) throw new IllegalStateException(failed + " seeded jobs could not be paused");
            }
        }
        System.out.printf(
                "Seeded team %s, %d reusable jobs and %d cron jobs; private session: %s%n",
                "bench-" + suffix, reusable.size(), cron.size(), files.path("session.json"));
        return 0;
    }
}
