package dev.kairos.http.client;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;

/** An in-JVM HTTPS server on the loopback interface that counts hits per path and records the last request. */
final class TestTlsServer implements AutoCloseable {

    private final HttpsServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final Map<String, Recorded> last = new ConcurrentHashMap<>();

    /** One received request. */
    record Recorded(String method, String rawQuery, Headers headers, String body) {}

    TestTlsServer(SSLContext context) throws IOException {
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.setExecutor(executor);
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    void handle(String path, HttpHandler handler) {
        server.createContext(path, exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            last.put(
                    path,
                    new Recorded(
                            exchange.getRequestMethod(),
                            exchange.getRequestURI().getRawQuery(),
                            exchange.getRequestHeaders(),
                            new String(body, StandardCharsets.UTF_8)));
            handler.handle(exchange);
        });
    }

    int hits(String path) {
        AtomicInteger count = hits.get(path);
        return count == null ? 0 : count.get();
    }

    Recorded last(String path) {
        return last.get(path);
    }

    static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
