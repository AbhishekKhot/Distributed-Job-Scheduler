package dev.kairos.mocktarget;

import static org.assertj.core.api.Assertions.*;

import dev.kairos.mocktarget.behavior.application.CallTracker;
import dev.kairos.mocktarget.config.DevelopmentTls;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "kairos.mock-target.snapshot-interval=1s"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MockTargetHttpsIntegrationTest {
    static final byte[] KEY = new byte[32];

    static {
        new SecureRandom().nextBytes(KEY);
    }

    static final Path SNAPSHOT = Path.of("/tmp/mock-test-" + UUID.randomUUID(), "stats.json");

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry properties) {
        properties.add(
                "kairos.mock-target.verification-keys",
                () -> "ksig_v1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(KEY));
        properties.add("kairos.mock-target.snapshot-path", () -> SNAPSHOT.toString());
    }

    @Value("${local.server.port}")
    int port;

    @Value("${local.management.port}")
    int managementPort;

    @Autowired
    CallTracker tracker;

    @Autowired
    DevelopmentTls tls;

    HttpClient client;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeAll
    void trustOnlyPublicCa() throws Exception {
        try (var plain = HttpClient.newHttpClient()) {
            var ca = plain.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/ca.pem"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(ca.statusCode()).isEqualTo(200);
            assertThat(ca.body()).isEqualTo(tls.caPem()).doesNotContain("PRIVATE KEY");
            var store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            store.setCertificateEntry(
                    "mock-ca",
                    CertificateFactory.getInstance("X.509")
                            .generateCertificate(
                                    new ByteArrayInputStream(ca.body().getBytes(StandardCharsets.US_ASCII))));
            var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(store);
            var context = SSLContext.getInstance("TLS");
            context.init(null, managers.getTrustManagers(), null);
            client = HttpClient.newBuilder()
                    .sslContext(context)
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
        }
    }

    @AfterAll
    void close() throws Exception {
        if (client != null) client.close();
        Files.deleteIfExists(SNAPSHOT);
        Files.deleteIfExists(SNAPSHOT.getParent());
    }

    private HttpResponse<byte[]> request(
            String method, String path, String key, byte[] body, boolean valid, String contentType) throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String canonical = method + "\n" + path + "\n" + timestamp + "\n" + key + "\n"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        String signature = "v1=" + HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        return client.send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(5))
                        .header("Idempotency-Key", key)
                        .header("X-Kairos-Timestamp", timestamp)
                        .header("X-Kairos-Signature", valid ? signature : "v1=00")
                        .header("Content-Type", contentType)
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void verifiedTlsSignedBodyEncodedQueryAndExactResponseLengthWork() throws Exception {
        var result = request(
                "POST",
                "/behave/healthy?status=201&bodyBytes=%31%32%33",
                UUID.randomUUID().toString(),
                "payload".getBytes(StandardCharsets.UTF_8),
                true,
                "application/json");
        assertThat(result.statusCode()).isEqualTo(201);
        assertThat(result.body()).hasSize(123);
    }

    @Test
    void verifiesFormBodyWithoutConsumingItAsBehaviorParameters() throws Exception {
        assertThat(request(
                                "POST",
                                "/behave",
                                UUID.randomUUID().toString(),
                                "a=1&secret=not-echoed".getBytes(StandardCharsets.UTF_8),
                                true,
                                "application/x-www-form-urlencoded")
                        .statusCode())
                .isEqualTo(200);
    }

    @Test
    void untrustedClientCannotBypassCertificateVerification() {
        try (var untrusted = HttpClient.newHttpClient()) {
            assertThatThrownBy(() -> untrusted.send(
                            HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/behave"))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOf(javax.net.ssl.SSLHandshakeException.class);
        }
    }

    @Test
    void duplicateAndSignatureFailureCountersMatchReceivedCalls() throws Exception {
        String key = UUID.randomUUID().toString();
        var before = tracker.stats();
        assertThat(request("GET", "/behave", key, new byte[0], true, "application/json")
                        .statusCode())
                .isEqualTo(200);
        assertThat(request("GET", "/behave", key, new byte[0], false, "application/json")
                        .statusCode())
                .isEqualTo(401);
        var after = tracker.stats();
        assertThat(after.totalCalls() - before.totalCalls()).isEqualTo(2);
        assertThat(after.uniqueKeys() - before.uniqueKeys()).isEqualTo(1);
        assertThat(after.duplicateKeys() - before.duplicateKeys()).isEqualTo(1);
        assertThat(after.signatureFailures() - before.signatureFailures()).isEqualTo(1);
        try (var plain = HttpClient.newHttpClient()) {
            var response = plain.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/stats"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).path("duplicateKeys").asLong())
                    .isEqualTo(after.duplicateKeys());
            assertThat(response.body()).doesNotContain(key, "ksig_");
            var snapshot = plain.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/stats/snapshot"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(snapshot.statusCode()).isEqualTo(200);
            assertThat(json.readTree(snapshot.body()).path("counts").path(key).asLong())
                    .isEqualTo(2);
        }
    }

    @Test
    void managementRoutesAreAbsentFromTheHttpsTarget() throws Exception {
        assertThat(request("GET", "/stats", UUID.randomUUID().toString(), new byte[0], true, "application/json")
                        .statusCode())
                .isEqualTo(404);
        assertThat(request("GET", "/ca.pem", UUID.randomUUID().toString(), new byte[0], true, "application/json")
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    void invalidRequestsAreBoundedAndDoNotEchoPayloads() throws Exception {
        assertThat(request(
                                "GET",
                                "/behave?status=700",
                                UUID.randomUUID().toString(),
                                new byte[0],
                                true,
                                "application/json")
                        .statusCode())
                .isEqualTo(400);
        var result = request(
                "POST", "/behave", UUID.randomUUID().toString(), new byte[1_048_577], true, "application/octet-stream");
        assertThat(result.statusCode()).isEqualTo(413);
        assertThat(result.body()).isEmpty();
    }

    @Test
    void failureInjectionAndBodylessStatusesWork() throws Exception {
        assertThat(request(
                                "GET",
                                "/behave?failRate=1",
                                UUID.randomUUID().toString(),
                                new byte[0],
                                true,
                                "application/json")
                        .statusCode())
                .isEqualTo(503);
        assertThat(request(
                                "GET",
                                "/behave?status=204",
                                UUID.randomUUID().toString(),
                                new byte[0],
                                true,
                                "application/json")
                        .body())
                .isEmpty();
        assertThat(request(
                                "HEAD",
                                "/behave?bodyBytes=1024",
                                UUID.randomUUID().toString(),
                                new byte[0],
                                true,
                                "application/json")
                        .body())
                .isEmpty();
        assertThat(request(
                                "GET",
                                "/behave?bodyBytes=0&latencyMs=1",
                                UUID.randomUUID().toString(),
                                new byte[0],
                                true,
                                "application/json")
                        .body())
                .isEmpty();
    }

    @Test
    void scheduledSnapshotContainsCoherentCounts() throws Exception {
        request("GET", "/behave", UUID.randomUUID().toString(), new byte[0], true, "application/json");
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(Files.exists(SNAPSHOT)).isTrue();
            var root = json.readTree(SNAPSHOT.toFile());
            assertThat(root.path("version").asInt()).isEqualTo(1);
            assertThat(root.path("stats").path("totalCalls").asLong()).isGreaterThan(0);
        });
    }
}
