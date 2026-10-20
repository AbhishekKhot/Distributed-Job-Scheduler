package dev.kairos.http.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TargetPolicyTest {

    private final TargetPolicy policy = TargetPolicy.builder()
            .allowSuffix(".svc.cluster.local")
            .allowSuffix("internal")
            .allowHost("payments.example.com")
            .allowCidr(Cidr.parse("10.0.0.0/8"))
            .allowCidr(Cidr.parse("fc00::/7"))
            .allowPort(443)
            .allowPort(8443)
            .denyInfrastructure("postgres.svc.cluster.local")
            .denyInfrastructure("10.96.0.1")
            .denyCidr(Cidr.parse("10.200.0.0/16"))
            .build();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://orders.payments.svc.cluster.local:8443/v1/orders/42",
                "https://billing.internal/v1",
                "HTTPS://Billing.Internal/v1?x=1",
                "https://payments.example.com/",
                "https://10.1.2.3/",
                "https://[fd00::1]:8443/x"
            })
    void allowsInternalTargets(String url) {
        assertThat(policy.checkUrl(URI.create(url)).isAllowed()).isTrue();
    }

    @Test
    void normalisesTheHostAndDefaultsThePort() {
        assertThat(policy.checkUrl(URI.create("https://Billing.INTERNAL/v1")))
                .isEqualTo(new TargetDecision.Allowed("billing.internal", 443));
    }

    @Test
    void refusesEverythingButHttps() {
        assertDenied("http://billing.internal/", DenyReason.NOT_HTTPS);
        assertDenied("ftp://billing.internal/", DenyReason.NOT_HTTPS);
        assertDenied("file:///etc/passwd", DenyReason.NOT_HTTPS);
        assertDenied("/relative", DenyReason.MALFORMED_URL);
        assertDenied("mailto:a@b.internal", DenyReason.MALFORMED_URL);
    }

    @Test
    void refusesUserInfoFragmentsAndOddAuthorities() {
        assertDenied("https://user:pw@billing.internal/", DenyReason.USER_INFO);
        assertDenied("https://billing.internal/#frag", DenyReason.MALFORMED_URL);
        assertDenied("https://bill%69ng.internal/", DenyReason.MALFORMED_URL);
        assertDenied("https://billing_x.internal/", DenyReason.MALFORMED_URL);
        assertDenied("https://billing.internal:/", DenyReason.MALFORMED_URL);
        assertDenied("https://billing.internal:99999/", DenyReason.MALFORMED_URL);
        assertDenied("https://billing.internal/" + "a".repeat(2100), DenyReason.MALFORMED_URL);
    }

    @Test
    void refusesPortsThatAreNotAllowed() {
        assertDenied("https://billing.internal:22/", DenyReason.PORT_NOT_ALLOWED);
        assertDenied("https://billing.internal:80/", DenyReason.PORT_NOT_ALLOWED);
    }

    @Test
    void refusesHostsOutsideTheAllowList() {
        assertDenied("https://example.org/", DenyReason.HOST_NOT_ALLOWED);
        assertDenied("https://internal/", DenyReason.HOST_NOT_ALLOWED);
        assertDenied("https://evilinternal/", DenyReason.HOST_NOT_ALLOWED);
        assertDenied("https://example.com/", DenyReason.HOST_NOT_ALLOWED);
    }

    @Test
    void refusesInvalidHostNames() {
        assertThat(policy.checkHostName("billing.internal.")).contains(DenyReason.INVALID_HOST);
        assertThat(policy.checkHostName("-billing.internal")).contains(DenyReason.INVALID_HOST);
        assertThat(policy.checkHostName("bïlling.internal")).contains(DenyReason.INVALID_HOST);
        assertThat(policy.checkHostName("a..internal")).contains(DenyReason.INVALID_HOST);
        assertThat(policy.checkHostName("a".repeat(64) + ".internal")).contains(DenyReason.INVALID_HOST);
        assertThat(policy.checkHostName("")).contains(DenyReason.INVALID_HOST);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://metadata.google.internal/computeMetadata/v1/",
                "https://METADATA.google.internal/",
                "https://x.metadata.google.internal/",
                "https://metadata/",
                "https://localhost/",
                "https://a.localhost/",
                "https://kubernetes.default.svc.cluster.local/",
                "https://kubernetes.default.svc.corp.internal/",
                "https://postgres.svc.cluster.local/"
            })
    void refusesFixedAndInfrastructureHostNames(String url) {
        assertThat(policy.checkUrl(URI.create(url))).isInstanceOf(TargetDecision.Denied.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://127.0.0.1/",
                "https://169.254.169.254/",
                "https://0.0.0.0/",
                "https://224.0.0.1/",
                "https://255.255.255.255/",
                "https://[::1]/",
                "https://[::]/",
                "https://[fe80::1]/",
                "https://[ff02::1]/",
                "https://[::ffff:127.0.0.1]/",
                "https://[::ffff:169.254.169.254]/",
                "https://[::ffff:a9fe:a9fe]/",
                "https://[64:ff9b::a9fe:a9fe]/",
                "https://[2002:a9fe:a9fe::1]/",
                "https://[::127.0.0.1]/",
                "https://10.96.0.1/",
                "https://10.200.3.4/"
            })
    void refusesDeniedAddresses(String url) {
        assertDenied(url, DenyReason.DENIED_ADDRESS);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "127.1",
                "2130706433",
                "0x7f.0.0.1",
                "0x7f000001",
                "0177.0.0.1",
                "010.1.2.3",
                "10.1.2",
                "10.1.2.3.4",
                "10.1.2.256",
                "1.2.3.4e",
                "[127.0.0.1]",
                "[fd00::1]x"
            })
    void refusesAmbiguousIpLiterals(String host) {
        assertThat(policy.checkHostName(host)).contains(DenyReason.AMBIGUOUS_IP_LITERAL);
        assertThat(policy.checkHost(host, 443)).isEqualTo(new TargetDecision.Denied(DenyReason.AMBIGUOUS_IP_LITERAL));
    }

    @Test
    void refusesIpv6ZoneIds() {
        assertThat(policy.checkHostName("[fd00::1%25eth0]")).contains(DenyReason.AMBIGUOUS_IP_LITERAL);
        assertThat(policy.checkHostName("[fd00::1%eth0]")).contains(DenyReason.AMBIGUOUS_IP_LITERAL);
        assertThat(policy.checkHostName("[fd00::1.2.3]")).contains(DenyReason.AMBIGUOUS_IP_LITERAL);
    }

    @Test
    void refusesPublicAddressesOutsideTheAllowedRanges() {
        assertDenied("https://8.8.8.8/", DenyReason.ADDRESS_NOT_ALLOWED);
        assertDenied("https://[2001:db8::1]/", DenyReason.ADDRESS_NOT_ALLOWED);
        assertThat(policy.checkAddress(address("192.168.1.1"))).contains(DenyReason.ADDRESS_NOT_ALLOWED);
    }

    @Test
    void theFixedDenyListCannotBeConfiguredAway() {
        TargetPolicy permissive = TargetPolicy.builder()
                .allowCidr(Cidr.parse("0.0.0.0/0"))
                .allowCidr(Cidr.parse("::/0"))
                .allowSuffix("google.internal")
                .allowHost("localhost")
                .allowHost("metadata.google.internal")
                .allowPort(443)
                .build();

        assertThat(permissive.checkAddress(address("127.0.0.1"))).contains(DenyReason.DENIED_ADDRESS);
        assertThat(permissive.checkAddress(address("169.254.169.254"))).contains(DenyReason.DENIED_ADDRESS);
        assertThat(permissive.checkAddress(address("::1"))).contains(DenyReason.DENIED_ADDRESS);
        assertThat(permissive.checkHostName("localhost")).contains(DenyReason.DENIED_HOST);
        assertThat(permissive.checkHostName("metadata.google.internal")).contains(DenyReason.DENIED_HOST);
        assertThat(permissive.checkAddress(address("8.8.8.8"))).isEmpty();
    }

    @Test
    void checksEveryResolvedAddress() {
        assertThat(policy.checkAddresses(List.of(address("10.1.1.1"), address("10.2.2.2"))))
                .isEmpty();
        assertThat(policy.checkAddresses(List.of(address("10.1.1.1"), address("127.0.0.1"))))
                .contains(DenyReason.DENIED_ADDRESS);
        assertThat(policy.checkAddresses(List.of())).contains(DenyReason.NO_ADDRESS);
    }

    @Test
    void anEmptyPolicyRefusesEverything() {
        TargetPolicy empty = TargetPolicy.builder().allowPort(443).build();

        assertThat(empty.checkUrl(URI.create("https://billing.internal/")))
                .isEqualTo(new TargetDecision.Denied(DenyReason.HOST_NOT_ALLOWED));
        assertThat(empty.checkAddress(address("10.0.0.1"))).contains(DenyReason.ADDRESS_NOT_ALLOWED);
    }

    @Test
    void theBuilderRejectsInvalidRules() {
        TargetPolicy.Builder builder = TargetPolicy.builder();

        assertThatThrownBy(() -> builder.allowPort(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.allowPort(65_536)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.allowSuffix("..")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.allowHost("bad host")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.denyInfrastructure("bad_host")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decisionsValidateTheirParts() {
        assertThatThrownBy(() -> new TargetDecision.Denied(null)).isInstanceOf(NullPointerException.class);
        assertThat(new TargetDecision.Denied(DenyReason.NO_ADDRESS).isAllowed()).isFalse();
        assertThat(new TargetNotAllowedException(DenyReason.DENIED_ADDRESS).reason())
                .isEqualTo(DenyReason.DENIED_ADDRESS);
        assertThat(new TargetNotAllowedException(DenyReason.DENIED_ADDRESS).getMessage())
                .isEqualTo("target not allowed: DENIED_ADDRESS");
    }

    private void assertDenied(String url, DenyReason reason) {
        assertThat(policy.checkUrl(URI.create(url))).isEqualTo(new TargetDecision.Denied(reason));
    }

    private static InetAddress address(String literal) {
        Optional<InetAddress> parsed = IpLiterals.parseAddress(literal);
        assertThat(parsed).isPresent();
        return parsed.get();
    }
}
