package dev.kairos.http.client;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.domain.retry.CallResult;
import dev.kairos.http.request.HttpMethod;
import dev.kairos.http.request.OutboundRequest;
import dev.kairos.testsupport.MutableClock;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Acceptance tests of T-110 against a local TLS server. */
class OutboundHttpClientTlsTest {

    private static TlsTestEnvironment env;
    private static final CountDownLatch RELEASE_SLOW = new CountDownLatch(1);

    private final MutableClock clock = MutableClock.at(Instant.parse("2026-10-20T10:00:00Z"));
    private final List<SendContext> sends = new ArrayList<>();
    private final SendObserver recording = sends::add;
    private HttpClientFactory factory;
    private OutboundHttpClient teamClient;

    @BeforeAll
    static void startServer() throws IOException {
        env = new TlsTestEnvironment();
        env.server.handle(
                "/json",
                exchange -> TestTlsServer.respond(
                        exchange, 200, "application/json; charset=utf-8", "{\"data\":{\"id\":\"o-1\"}}"));
        env.server.handle("/text", exchange -> TestTlsServer.respond(exchange, 404, "text/plain", "not here"));
        env.server.handle("/echo", exchange -> TestTlsServer.respond(exchange, 201, "application/json", "{}"));
        env.server.handle("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirect-target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        env.server.handle("/redirect-target", exchange -> TestTlsServer.respond(exchange, 200, "text/plain", "x"));
        env.server.handle("/big", exchange -> {
            byte[] body = new byte[300 * 1024];
            Arrays.fill(body, (byte) 'a');
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } catch (IOException e) {
                // The client aborts after 256 KB.
            }
        });
        env.server.handle("/rebind", exchange -> {
            exchange.getResponseHeaders().set("Connection", "close");
            TestTlsServer.respond(exchange, 200, "text/plain", "ok");
        });
        env.server.handle("/slow", exchange -> {
            try {
                RELEASE_SLOW.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            TestTlsServer.respond(exchange, 200, "text/plain", "late");
        });
        env.server.handle("/trickle", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                // One byte every 200 ms: never idle long enough for the response timeout, so only the deadline ends it.
                for (int i = 0; i < 100; i++) {
                    out.write('x');
                    out.flush();
                    Thread.sleep(200);
                }
            } catch (IOException e) {
                // The client gave up.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @AfterAll
    static void stopServer() {
        RELEASE_SLOW.countDown();
        env.close();
    }

    @BeforeEach
    void createFactory() {
        factory = env.factory(clock);
        teamClient = factory.forCaBundle(env.bundle("team-a/internal-ca"));
    }

    @AfterEach
    void closeFactory() {
        factory.close();
    }

    @Test
    void callsTheTargetThroughTheTeamCaBundleAndDecodesJson() {
        HttpCallOutcome outcome = teamClient.execute(get(env.url("orders.test.internal", "/json")), recording);

        assertThat(outcome).isInstanceOf(HttpCallOutcome.Received.class);
        HttpCallOutcome.Received received = (HttpCallOutcome.Received) outcome;
        assertThat(received.status()).isEqualTo(200);
        assertThat(received.body().json()).isPresent();
        assertThat(received.body().json().get().at("/data/id").stringValue()).isEqualTo("o-1");
        assertThat(received.header("content-type")).contains("application/json; charset=utf-8");
        assertThat(received.requestSent()).isTrue();
        assertThat(received.callResult()).contains(new CallResult.Responded(200));
        assertThat(received.startedAt()).isEqualTo(clock.instant());
        assertThat(sends).containsExactly(new SendContext(HttpMethod.GET, "orders.test.internal", env.server.port()));
    }

    @Test
    void aNonJsonBodyHasNoTree() {
        HttpCallOutcome.Received received =
                (HttpCallOutcome.Received) teamClient.execute(get(env.url("orders.test.internal", "/text")));

        assertThat(received.status()).isEqualTo(404);
        assertThat(received.body().text()).isEqualTo("not here");
        assertThat(received.body().json()).isEmpty();
    }

    @Test
    void sendsMethodHeadersAndBody() {
        OutboundRequest request = OutboundRequest.builder(
                        HttpMethod.POST,
                        URI.create(env.url("orders.test.internal", "/echo?a=1")),
                        Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Source", "kairos")
                .body("{\"n\":1}".getBytes(StandardCharsets.UTF_8))
                .build();

        HttpCallOutcome outcome = teamClient.execute(request);

        assertThat(outcome).isInstanceOf(HttpCallOutcome.Received.class);
        TestTlsServer.Recorded recorded = env.server.last("/echo");
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.rawQuery()).isEqualTo("a=1");
        assertThat(recorded.headers().getFirst("X-Source")).isEqualTo("kairos");
        assertThat(recorded.headers().getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(recorded.headers().getFirst("User-Agent")).isEqualTo(OutboundHttpClient.USER_AGENT);
        assertThat(recorded.body()).isEqualTo("{\"n\":1}");
    }

    @Test
    void headRequestsHaveNoBody() {
        OutboundRequest head = OutboundRequest.builder(
                        HttpMethod.HEAD, URI.create(env.url("orders.test.internal", "/json")), Duration.ofSeconds(5))
                .build();

        HttpCallOutcome.Received received = (HttpCallOutcome.Received) teamClient.execute(head);

        assertThat(received.status()).isEqualTo(200);
        assertThat(received.body().size()).isZero();
    }

    @Test
    void refusesPlainHttp() {
        HttpCallOutcome outcome =
                teamClient.execute(get("http://orders.test.internal:" + env.server.port() + "/json"), recording);

        assertTargetNotAllowed(outcome);
    }

    @Test
    void refusesAHostResolvingToLoopback() {
        assertTargetNotAllowed(teamClient.execute(get(env.url("loop.test.internal", "/json")), recording));
    }

    @Test
    void refusesAHostResolvingToTheMetadataAddress() {
        assertTargetNotAllowed(teamClient.execute(get(env.url("meta.test.internal", "/json")), recording));
        assertTargetNotAllowed(teamClient.execute(get("https://169.254.169.254/computeMetadata/v1/"), recording));
        assertTargetNotAllowed(teamClient.execute(get("https://metadata.google.internal/"), recording));
    }

    @Test
    void refusesARebindingHost() {
        HttpCallOutcome first = teamClient.execute(get(env.url("rebind.test.internal", "/rebind")));
        HttpCallOutcome second = teamClient.execute(get(env.url("rebind.test.internal", "/rebind")), recording);

        assertThat(first).isInstanceOf(HttpCallOutcome.Received.class);
        assertTargetNotAllowed(second);
        assertThat(env.rebindLookups.get()).isGreaterThanOrEqualTo(2);
        assertThat(env.server.hits("/rebind")).isEqualTo(1);
    }

    @Test
    void doesNotFollowRedirects() {
        HttpCallOutcome.Received received =
                (HttpCallOutcome.Received) teamClient.execute(get(env.url("orders.test.internal", "/redirect")));

        assertThat(received.status()).isEqualTo(302);
        assertThat(received.header("Location")).contains("/redirect-target");
        assertThat(env.server.hits("/redirect-target")).isZero();
    }

    @Test
    void cutsOffABodyOverTheCap() {
        HttpCallOutcome outcome = teamClient.execute(get(env.url("orders.test.internal", "/big")));

        assertThat(outcome).isInstanceOf(HttpCallOutcome.TooLarge.class);
        assertThat(outcome.requestSent()).isTrue();
        assertThat(outcome.callResult()).contains(new CallResult.ResponseTooLarge(200));
        // The connection was discarded, not drained: the client still works.
        assertThat(teamClient.execute(get(env.url("orders.test.internal", "/json"))))
                .isInstanceOf(HttpCallOutcome.Received.class);
    }

    @Test
    void failsTlsWhenTheCertificateIsForAnotherHost() {
        HttpCallOutcome outcome = teamClient.execute(get(env.url("other.test.internal", "/json")), recording);

        assertThat(outcome.callResult()).contains(new CallResult.TlsFailed());
        assertThat(outcome.requestSent()).isFalse();
        assertThat(sends).isEmpty();
    }

    @Test
    void trustsATeamCaBundleOnlyForThatTeamsClient() {
        HttpCallOutcome withoutBundle = factory.defaultClient().execute(get(env.url("orders.test.internal", "/json")));
        HttpCallOutcome otherTeam = factory.forCaBundle(env.otherBundle("team-b/their-ca"))
                .execute(get(env.url("orders.test.internal", "/json")));
        HttpCallOutcome sameCaOtherTeam =
                factory.forCaBundle(env.bundle("team-c/ca")).execute(get(env.url("orders.test.internal", "/json")));

        assertThat(withoutBundle.callResult()).contains(new CallResult.TlsFailed());
        assertThat(otherTeam.callResult()).contains(new CallResult.TlsFailed());
        assertThat(sameCaOtherTeam).isInstanceOf(HttpCallOutcome.Received.class);
        assertThat(factory.forCaBundle(env.bundle("team-a/internal-ca"))).isSameAs(teamClient);
        assertThat(factory.forCaBundle(env.bundle("team-c/ca"))).isNotSameAs(teamClient);
    }

    @Test
    void aVetoStopsTheRequestBeforeAnyByteIsSent() {
        int before = env.server.hits("/json");

        HttpCallOutcome outcome = teamClient.execute(get(env.url("orders.test.internal", "/json")), context -> {
            throw new SendVetoedException("lock has too little time left");
        });

        assertThat(outcome).isInstanceOf(HttpCallOutcome.NotSent.class);
        assertThat(((HttpCallOutcome.NotSent) outcome).detail()).isEqualTo("lock has too little time left");
        assertThat(outcome.requestSent()).isFalse();
        assertThat(outcome.callResult()).isEmpty();
        assertThat(env.server.hits("/json")).isEqualTo(before);
    }

    @Test
    void aFailingObserverFailsClosed() {
        int before = env.server.hits("/json");

        HttpCallOutcome outcome = teamClient.execute(get(env.url("orders.test.internal", "/json")), context -> {
            throw new IllegalStateException("redis unavailable");
        });

        assertThat(outcome).isInstanceOf(HttpCallOutcome.NotSent.class);
        assertThat(((HttpCallOutcome.NotSent) outcome).detail()).doesNotContain("redis");
        assertThat(env.server.hits("/json")).isEqualTo(before);
    }

    @Test
    void aSilentTargetTimesOutAfterTheRequestWasSent() {
        HttpCallOutcome outcome = teamClient.execute(
                OutboundRequest.builder(
                                HttpMethod.GET,
                                URI.create(env.url("orders.test.internal", "/slow")),
                                Duration.ofSeconds(1))
                        .build(),
                recording);

        assertThat(outcome.callResult()).contains(new CallResult.TimedOut(true));
        assertThat(outcome.requestSent()).isTrue();
        assertThat(sends).hasSize(1);
    }

    @Test
    void aTricklingTargetIsCutOffAtTheDeadline() {
        HttpCallOutcome outcome = teamClient.execute(OutboundRequest.builder(
                        HttpMethod.GET, URI.create(env.url("orders.test.internal", "/trickle")), Duration.ofSeconds(1))
                .build());

        assertThat(outcome.callResult()).contains(new CallResult.TimedOut(true));
        assertThat(((HttpCallOutcome.Failed) outcome).detail()).contains("endpoint timeout");
    }

    @Test
    void reportsDnsFailure() {
        HttpCallOutcome outcome = teamClient.execute(get(env.url("missing.test.internal", "/json")), recording);

        assertThat(outcome.callResult()).contains(new CallResult.DnsFailed());
        assertThat(outcome.requestSent()).isFalse();
        assertThat(sends).isEmpty();
    }

    @Test
    void reportsARefusedConnectionAsNotSent() {
        HttpCallOutcome outcome = teamClient.execute(get(env.url("closed.test.internal", "/json")), recording);

        assertThat(outcome.callResult()).contains(new CallResult.ConnectionFailed(false));
        assertThat(outcome.requestSent()).isFalse();
        assertThat(sends).isEmpty();
    }

    private void assertTargetNotAllowed(HttpCallOutcome outcome) {
        assertThat(outcome.callResult()).contains(new CallResult.TargetNotAllowed());
        assertThat(outcome.requestSent()).isFalse();
        assertThat(((HttpCallOutcome.Failed) outcome).detail()).startsWith("target not allowed");
        assertThat(sends).isEmpty();
    }

    private static OutboundRequest get(String url) {
        return OutboundRequest.builder(HttpMethod.GET, URI.create(url), Duration.ofSeconds(5))
                .build();
    }
}
