package dev.kairos.bench.scenario;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/** Isolated team and bounded waits shared by scenario flows. */
public final class ScenarioEnvironment {
    private static final SecureRandom RANDOM = new SecureRandom();
    final ScenarioHttp api;
    final ScenarioHttp target;
    final String targetUrl;
    final String suffix;
    final String userToken;
    final String apiKey;
    final String apiKeyId;
    final String teamId;
    final String caName;

    private ScenarioEnvironment(
            ScenarioHttp api,
            ScenarioHttp target,
            String targetUrl,
            String suffix,
            String userToken,
            String apiKey,
            String apiKeyId,
            String teamId,
            String caName) {
        this.api = api;
        this.target = target;
        this.targetUrl = targetUrl;
        this.suffix = suffix;
        this.userToken = userToken;
        this.apiKey = apiKey;
        this.apiKeyId = apiKeyId;
        this.teamId = teamId;
        this.caName = caName;
    }

    public static ScenarioEnvironment create(ScenarioHttp api, ScenarioHttp target, String targetUrl) throws Exception {
        byte[] random = new byte[8];
        RANDOM.nextBytes(random);
        String suffix = HexFormat.of().formatHex(random);
        String email = "scenario-" + suffix + "@kairos.test";
        String password = "A1!" + suffix + "abcdef";
        api.post("v1/auth/signup", Map.of("email", email, "password", password), null)
                .expect(201);
        String initial = api.post("v1/auth/login", Map.of("email", email, "password", password), null)
                .expect(200)
                .path("accessToken")
                .asString();
        String teamId = api.post("v1/teams", Map.of("name", "scenario-" + suffix), initial)
                .expect(201)
                .path("teamId")
                .asString();
        String token = api.post("v1/auth/login", Map.of("email", email, "password", password), null)
                .expect(200)
                .path("accessToken")
                .asString();
        var createdKey = api.post(
                        "v1/api-keys",
                        Map.of(
                                "name",
                                "scenario-" + suffix,
                                "expiresAt",
                                Instant.now().plus(Duration.ofDays(1)).toString()),
                        token)
                .expect(201);
        String key = createdKey.path("key").asString();
        String keyId = createdKey.path("keyId").asString();
        String caName = "scenario-ca-" + suffix;
        api.post("v1/ca-bundles", Map.of("name", caName, "pem", target.text("ca.pem")), key)
                .expect(201);
        return new ScenarioEnvironment(api, target, targetUrl, suffix, token, key, keyId, teamId, caName);
    }

    public String endpoint(String label, String path, Map<String, Object> extras) throws Exception {
        String name = "scenario-" + label + "-" + suffix;
        return api.post("v1/endpoints", endpointBody(name, path, extras), apiKey)
                .expect(201)
                .path("id")
                .asString();
    }

    public Map<String, Object> endpointBody(String name, String path, Map<String, Object> extras) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("method", "POST");
        body.put("urlTemplate", targetUrl + path);
        body.put("caBundle", caName);
        body.put("timeoutMs", 10_000);
        body.put(
                "retryPolicy",
                Map.of("maxRetries", 0, "strategy", "FIXED", "baseDelaySec", 1, "maxDelaySec", 1, "maxAgeSec", 60));
        body.put("limits", Map.of("maxConcurrency", 100, "ratePerSec", 0));
        body.putAll(extras);
        return body;
    }

    public Map<String, Object> job(String label, String endpoint, Map<String, Object> schedule) {
        return Map.of(
                "name",
                "scenario-" + label + "-" + suffix,
                "endpoint",
                endpoint,
                "schedule",
                schedule,
                "priority",
                5,
                "request",
                Map.of("pathParams", Map.of(), "query", Map.of(), "headers", Map.of()));
    }

    public String createJob(String label, String endpoint, Map<String, Object> schedule) throws Exception {
        return api.post("v1/jobs", job(label, endpoint, schedule), apiKey)
                .expect(201)
                .path("id")
                .asString();
    }

    public String awaitJobRun(String jobId, String status, Duration timeout) throws Exception {
        return await(timeout, () -> {
            var items =
                    api.get("v1/jobs/" + jobId + "/runs", apiKey).expect(200).path("items");
            for (var item : items) {
                if (status.equals(item.path("status").asString()))
                    return item.path("runId").asString();
            }
            return null;
        });
    }

    public static <T> T await(Duration timeout, Callable<T> condition) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        Exception last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                T value = condition.call();
                if (value != null) return value;
            } catch (Exception failure) {
                last = failure;
            }
            Thread.sleep(250);
        }
        if (last != null) throw new IllegalStateException("Condition timed out", last);
        throw new IllegalStateException("Condition timed out after " + timeout);
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public static void requireStatus(ScenarioHttp.Response response, int expected) throws IOException {
        response.expect(expected);
    }
}
