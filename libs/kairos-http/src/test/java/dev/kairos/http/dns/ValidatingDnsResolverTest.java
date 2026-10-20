package dev.kairos.http.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.http.policy.Cidr;
import dev.kairos.http.policy.DenyReason;
import dev.kairos.http.policy.TargetNotAllowedException;
import dev.kairos.http.policy.TargetPolicy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ValidatingDnsResolverTest {

    private final TargetPolicy policy = TargetPolicy.builder()
            .allowSuffix(".internal")
            .allowCidr(Cidr.parse("10.0.0.0/8"))
            .allowPort(443)
            .build();

    private final List<String> looked = new ArrayList<>();

    @Test
    void returnsTheCheckedAddressesUnderTheOriginalName() throws UnknownHostException {
        ValidatingDnsResolver resolver = resolver(host -> List.of(ip("10.0.0.5"), ip("10.0.0.6")));

        InetAddress[] addresses = resolver.resolve("Orders.Internal");

        assertThat(addresses).containsExactly(ip("10.0.0.5"), ip("10.0.0.6"));
        assertThat(addresses[0].getHostName()).isEqualTo("orders.internal");
        assertThat(looked).containsExactly("orders.internal");
    }

    @Test
    void resolvesWithPort() throws UnknownHostException {
        ValidatingDnsResolver resolver = resolver(host -> List.of(ip("10.0.0.5")));

        assertThat(resolver.resolve("orders.internal", 443))
                .containsExactly(new InetSocketAddress(ip("10.0.0.5"), 443));
        assertThatThrownBy(() -> resolver.resolve("orders.internal", 22))
                .isInstanceOf(TargetNotAllowedException.class)
                .extracting(e -> ((TargetNotAllowedException) e).reason())
                .isEqualTo(DenyReason.PORT_NOT_ALLOWED);
    }

    @Test
    void refusesAHostWithAnyDeniedAddress() {
        ValidatingDnsResolver resolver = resolver(host -> List.of(ip("10.0.0.5"), ip("127.0.0.1")));

        assertThatThrownBy(() -> resolver.resolve("orders.internal"))
                .isInstanceOf(TargetNotAllowedException.class)
                .extracting(e -> ((TargetNotAllowedException) e).reason())
                .isEqualTo(DenyReason.DENIED_ADDRESS);
    }

    @Test
    void refusesARebindingAnswer() throws UnknownHostException {
        AtomicInteger calls = new AtomicInteger();
        ValidatingDnsResolver resolver =
                resolver(host -> List.of(calls.getAndIncrement() == 0 ? ip("10.0.0.5") : ip("169.254.169.254")));

        assertThat(resolver.resolve("orders.internal")).containsExactly(ip("10.0.0.5"));
        assertThatThrownBy(() -> resolver.resolve("orders.internal")).isInstanceOf(TargetNotAllowedException.class);
    }

    @Test
    void refusesMappedAndEmptyAnswers() {
        assertThatThrownBy(
                        () -> resolver(host -> List.of(ip("::ffff:127.0.0.1"))).resolve("orders.internal"))
                .isInstanceOf(TargetNotAllowedException.class);
        assertThatThrownBy(() -> resolver(host -> List.of()).resolve("orders.internal"))
                .isInstanceOf(TargetNotAllowedException.class)
                .extracting(e -> ((TargetNotAllowedException) e).reason())
                .isEqualTo(DenyReason.NO_ADDRESS);
    }

    @Test
    void neverLooksUpARefusedName() {
        ValidatingDnsResolver resolver = resolver(host -> List.of(ip("10.0.0.5")));

        assertThatThrownBy(() -> resolver.resolve("metadata.google.internal"))
                .isInstanceOf(TargetNotAllowedException.class);
        assertThatThrownBy(() -> resolver.resolve("example.org")).isInstanceOf(TargetNotAllowedException.class);
        assertThat(looked).isEmpty();
    }

    @Test
    void checksLiteralsWithoutALookup() throws UnknownHostException {
        ValidatingDnsResolver resolver = resolver(host -> List.of(ip("10.0.0.5")));

        assertThat(resolver.resolve("10.1.1.1")).containsExactly(ip("10.1.1.1"));
        assertThatThrownBy(() -> resolver.resolve("127.0.0.1")).isInstanceOf(TargetNotAllowedException.class);
        assertThatThrownBy(() -> resolver.resolve("127.1")).isInstanceOf(TargetNotAllowedException.class);
        assertThat(looked).isEmpty();
    }

    @Test
    void passesDnsFailuresThrough() {
        ValidatingDnsResolver resolver = resolver(host -> {
            throw new UnknownHostException("nxdomain");
        });

        assertThatThrownBy(() -> resolver.resolve("orders.internal"))
                .isInstanceOf(UnknownHostException.class)
                .isNotInstanceOf(TargetNotAllowedException.class);
    }

    @Test
    void neverReverseResolves() throws UnknownHostException {
        ValidatingDnsResolver resolver = resolver(host -> List.of(ip("10.0.0.5")));

        assertThat(resolver.resolveCanonicalHostname("orders.internal")).isEqualTo("orders.internal");
        assertThatThrownBy(() -> resolver.resolveCanonicalHostname("localhost"))
                .isInstanceOf(TargetNotAllowedException.class);
        assertThat(looked).isEmpty();
    }

    @Test
    void theSystemLookupResolvesLiterals() throws UnknownHostException {
        assertThat(HostLookup.system().lookup("10.0.0.1")).containsExactly(ip("10.0.0.1"));
    }

    private ValidatingDnsResolver resolver(HostLookup lookup) {
        return new ValidatingDnsResolver(policy, host -> {
            looked.add(host);
            return lookup.lookup(host);
        });
    }

    private static InetAddress ip(String literal) {
        return InetAddress.ofLiteral(literal);
    }
}
