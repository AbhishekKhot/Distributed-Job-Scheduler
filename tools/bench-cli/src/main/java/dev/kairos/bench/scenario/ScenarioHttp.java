package dev.kairos.bench.scenario;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP adapter that keeps response status visible for negative authorization checks. */
public final class ScenarioHttp implements AutoCloseable {
    static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final URI base;

    public ScenarioHttp(String base) {
        this.base = URI.create(base.endsWith("/") ? base : base + "/");
    }

    public Response request(String method, String path, Object body, String token, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        headers.forEach(builder::header);
        if (body != null) {
            builder.header("Content-Type", "application/json");
            if (method.equals("POST") && path.startsWith("v1/jobs"))
                builder.header("Idempotency-Key", UUID.randomUUID().toString());
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        } else builder.method(method, HttpRequest.BodyPublishers.noBody());
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        JsonNode json = response.body().length == 0 ? JSON.readTree("{}") : JSON.readTree(response.body());
        return new Response(
                response.statusCode(),
                json,
                response.headers().firstValue("ETag").orElse(""));
    }

    public Response get(String path, String token) throws IOException, InterruptedException {
        return request("GET", path, null, token, Map.of());
    }

    public Response post(String path, Object body, String token) throws IOException, InterruptedException {
        return request("POST", path, body, token, Map.of());
    }

    public Response put(String path, Object body, String token, String etag) throws IOException, InterruptedException {
        return request("PUT", path, body, token, Map.of("If-Match", etag));
    }

    public Response delete(String path, String token) throws IOException, InterruptedException {
        return request("DELETE", path, null, token, Map.of());
    }

    public String text(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(base.resolve(path))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " from " + path);
        return response.body();
    }

    @Override
    public void close() {
        client.close();
    }

    public record Response(int status, JsonNode body, String etag) {
        public JsonNode expect(int expected) throws IOException {
            if (status != expected)
                throw new IOException("Expected HTTP " + expected + ", got " + status + " ("
                        + body.path("type").asString() + ")");
            return body;
        }

        public String required(String name) throws IOException {
            JsonNode value = body.path(name);
            if (!value.isString() || value.asString().isBlank()) throw new IOException("Missing " + name);
            return value.asString();
        }
    }
}
