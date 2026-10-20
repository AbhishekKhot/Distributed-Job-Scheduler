package dev.kairos.http.client;

import dev.kairos.http.dns.HostLookup;
import dev.kairos.http.policy.Cidr;
import dev.kairos.http.policy.TargetPolicy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.io.DetachedSocketFactory;

/**
 * A local TLS target that the real policy, resolver and TLS checks can reach.
 *
 * <p>Test host names resolve to TEST-NET-3 addresses (203.0.113.0/24), which the test policy allows like any internal
 * range; the socket factory then connects those addresses to the loopback server. No policy rule is relaxed: loopback
 * and metadata addresses are still refused, and TLS still verifies the certificate chain and the original host name.
 */
final class TlsTestEnvironment implements AutoCloseable {

    static final InetAddress TARGET = address("203.0.113.10");
    static final InetAddress CLOSED = address("203.0.113.20");

    final TestPki.Issued ca = TestPki.ca("Kairos Test CA");
    final TestPki.Issued otherCa = TestPki.ca("Other Team CA");
    final TestTlsServer server;
    final int closedPort;
    final AtomicInteger rebindLookups = new AtomicInteger();
    final AtomicInteger socketsOpened = new AtomicInteger();

    TlsTestEnvironment() throws IOException {
        TestPki.Issued serverCert =
                TestPki.server(ca, "orders.test.internal", "rebind.test.internal", "token.test.internal");
        server = new TestTlsServer(TestPki.serverContext(serverCert, ca));
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
    }

    TargetPolicy policy() {
        return TargetPolicy.builder()
                .allowSuffix(".test.internal")
                .allowCidr(Cidr.parse("203.0.113.0/24"))
                .allowPort(server.port())
                .allowPort(443)
                .build();
    }

    HostLookup lookup() {
        Map<String, InetAddress> fixed = Map.of(
                "orders.test.internal", TARGET,
                "other.test.internal", TARGET,
                "token.test.internal", TARGET,
                "loop.test.internal", address("127.0.0.1"),
                "meta.test.internal", address("169.254.169.254"),
                "closed.test.internal", CLOSED);
        return host -> {
            if (host.equals("rebind.test.internal")) {
                // First answer public, every later answer loopback: a classic rebinding attack.
                return List.of(rebindLookups.getAndIncrement() == 0 ? TARGET : address("127.0.0.1"));
            }
            InetAddress address = fixed.get(host);
            if (address == null) {
                throw new UnknownHostException("no such test host");
            }
            return List.of(address);
        };
    }

    HttpClientFactory factory(Clock clock) {
        return new HttpClientFactory(policy(), lookup(), HttpClientSettings.defaults(), clock, sockets());
    }

    CaBundle bundle(String owner) {
        return CaBundle.parsePem(owner, TestPki.pem(ca.certificate()));
    }

    CaBundle otherBundle(String owner) {
        return CaBundle.parsePem(owner, TestPki.pem(otherCa.certificate()));
    }

    String url(String host, String path) {
        return "https://" + host + ":" + server.port() + path;
    }

    private DetachedSocketFactory sockets() {
        return proxy -> {
            socketsOpened.incrementAndGet();
            return new RemappingSocket();
        };
    }

    @Override
    public void close() {
        server.close();
    }

    private static InetAddress address(String literal) {
        return InetAddress.ofLiteral(literal);
    }

    /** Connects TEST-NET-3 targets to the loopback server and refuses every other destination. */
    private final class RemappingSocket extends Socket {

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            InetSocketAddress target = (InetSocketAddress) endpoint;
            InetAddress loopback = InetAddress.getLoopbackAddress();
            if (target.getAddress().equals(TARGET)) {
                super.connect(new InetSocketAddress(loopback, target.getPort()), timeout);
            } else if (target.getAddress().equals(CLOSED)) {
                super.connect(new InetSocketAddress(loopback, closedPort), timeout);
            } else {
                throw new IOException("the test socket only reaches test addresses");
            }
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, 0);
        }
    }
}
