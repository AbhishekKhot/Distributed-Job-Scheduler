package dev.kairos.bench.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenarioHttpTest {
    @Test
    void sendsJsonAndAuthorizationAndChecksResponses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/jobs", ScenarioHttpTest::respond);
        server.createContext("/plain", exchange -> send(exchange, 200, "ready"));
        server.start();
        try (ScenarioHttp api =
                new ScenarioHttp("http://127.0.0.1:" + server.getAddress().getPort())) {
            ScenarioHttp.Response created = api.post("v1/jobs", Map.of("name", "example"), "key");
            assertThat(created.status()).isEqualTo(201);
            assertThat(created.etag()).isEqualTo("\"v1\"");
            assertThat(created.required("id")).isEqualTo("job-1");
            assertThat(created.expect(201).path("authorized").asBoolean()).isTrue();
            assertThat(api.text("plain")).isEqualTo("ready");
            assertThatThrownBy(() -> created.expect(200))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("201");
            assertThatThrownBy(() -> created.required("missing"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("missing");
            assertThatThrownBy(() -> api.text("missing"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("404");
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        boolean authorized = "Bearer key".equals(exchange.getRequestHeaders().getFirst("Authorization"));
        boolean idempotent = exchange.getRequestHeaders().getFirst("Idempotency-Key") != null;
        boolean json = "{\"name\":\"example\"}"
                .equals(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        exchange.getResponseHeaders().set("ETag", "\"v1\"");
        send(exchange, 201, "{\"id\":\"job-1\",\"authorized\":" + (authorized && idempotent && json) + "}");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
