package dev.kairos.bench;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Small HTTP client for the public API and mock-target management routes. */
final class BenchApi {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final URI base;

    BenchApi(String base) {
        this.base = URI.create(base.endsWith("/") ? base : base + "/");
    }

    JsonNode post(String path, Object body, String token, boolean idempotent) throws IOException, InterruptedException {
        byte[] data = BenchFiles.JSON.writeValueAsBytes(body);
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(data));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (idempotent) request.header("Idempotency-Key", UUID.randomUUID().toString());
        return json(request.build());
    }

    JsonNode get(String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return json(request.build());
    }

    String text(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(), path);
        return response.body();
    }

    private JsonNode json(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            JsonNode problem = BenchFiles.JSON.readTree(response.body());
            throw new IOException("HTTP " + response.statusCode() + " from "
                    + request.uri().getPath() + ": " + problem.path("type").asString() + " " + problem.path("errors"));
        }
        return BenchFiles.JSON.readTree(response.body());
    }

    private static void requireSuccess(int status, String path) throws IOException {
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " from " + path);
        }
    }

    static String required(JsonNode value, String field) throws IOException {
        JsonNode child = value.path(field);
        if (!child.isString() || child.asString().isBlank()) throw new IOException("Missing " + field + " in response");
        return child.asString();
    }

    static Map<String, Object> job(String name, String endpoint, Map<String, Object> schedule) {
        return Map.of(
                "name",
                name,
                "endpoint",
                endpoint,
                "schedule",
                schedule,
                "request",
                Map.of("pathParams", Map.of(), "query", Map.of(), "headers", Map.of()),
                "priority",
                5,
                "misfirePolicy",
                "RUN_LATEST");
    }
}
