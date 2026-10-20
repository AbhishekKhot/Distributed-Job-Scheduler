package dev.kairos.http.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CidrAndLiteralsTest {

    @Test
    void containsAddressesInsideThePrefix() {
        Cidr ten = Cidr.parse("10.0.0.0/8");
        Cidr twelve = Cidr.parse("172.16.0.0/12");
        Cidr unique = Cidr.parse("fc00::/7");

        assertThat(ten.contains(literal("10.255.1.2"))).isTrue();
        assertThat(ten.contains(literal("11.0.0.1"))).isFalse();
        assertThat(twelve.contains(literal("172.31.255.255"))).isTrue();
        assertThat(twelve.contains(literal("172.32.0.0"))).isFalse();
        assertThat(unique.contains(literal("fd12::1"))).isTrue();
        assertThat(unique.contains(literal("fe00::1"))).isFalse();
        assertThat(unique.contains(literal("10.0.0.1"))).isFalse();
        assertThat(ten.contains(literal("fd12::1"))).isFalse();
        assertThat(Cidr.parse("0.0.0.0/0").contains(literal("8.8.8.8"))).isTrue();
    }

    @Test
    void foldsIpv4MappedAddresses() {
        assertThat(Cidr.parse("169.254.0.0/16").contains(literal("::ffff:169.254.169.254")))
                .isTrue();
        assertThat(IpLiterals.canonical(literal("::ffff:10.0.0.1"))).isInstanceOf(Inet4Address.class);
        assertThat(Cidr.host(literal("::ffff:10.0.0.1"))).isEqualTo(Cidr.parse("10.0.0.1/32"));
    }

    @Test
    void aBareAddressIsASingleHost() {
        assertThat(Cidr.parse("10.1.2.3").prefixLength()).isEqualTo(32);
        assertThat(Cidr.parse("fd00::1").prefixLength()).isEqualTo(128);
        assertThat(Cidr.parse("10.1.2.3")).isEqualTo(Cidr.parse("10.1.2.3/32"));
        assertThat(Cidr.parse("10.1.2.3")).hasSameHashCodeAs(Cidr.parse("10.1.2.3/32"));
        assertThat(Cidr.parse("10.0.0.0/8")).isNotEqualTo(Cidr.parse("10.0.0.0/9"));
        assertThat(Cidr.parse("10.0.0.0/8")).hasToString("10.0.0.0/8");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "10.0.0.1/33", "10.0.0.0/x", "10.0.0.0/", "10.0.0.1/8", "010.0.0.0/8", "fd00::/129", "x/8"})
    void rejectsInvalidCidrs(String text) {
        assertThatThrownBy(() -> Cidr.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlongCidrs() {
        assertThatThrownBy(() -> Cidr.parse("1".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cidr.parse("10.0.0.0/0008")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recognisesLiteralLookingHosts() {
        assertThat(IpLiterals.looksLikeLiteral("10.0.0.1")).isTrue();
        assertThat(IpLiterals.looksLikeLiteral("[::1]")).isTrue();
        assertThat(IpLiterals.looksLikeLiteral("::1")).isTrue();
        assertThat(IpLiterals.looksLikeLiteral("0x7f000001")).isTrue();
        assertThat(IpLiterals.looksLikeLiteral("1.2.3.4.nip.io")).isFalse();
        assertThat(IpLiterals.looksLikeLiteral("orders.internal")).isFalse();
        assertThat(IpLiterals.looksLikeLiteral("")).isFalse();
        assertThat(IpLiterals.looksLikeLiteral("orders.")).isFalse();
    }

    @Test
    void parsesOnlyStrictLiterals() {
        assertThat(IpLiterals.parseHost("10.0.0.1")).contains(literal("10.0.0.1"));
        assertThat(IpLiterals.parseHost("[fd00::1]")).contains(literal("fd00::1"));
        assertThat(IpLiterals.parseHost("fd00::1")).contains(literal("fd00::1"));
        assertThat(IpLiterals.parseHost("[::ffff:10.0.0.1]")).contains(literal("10.0.0.1"));
        assertThat(IpLiterals.parseHost("0.0.0.0")).isPresent();
        assertThat(IpLiterals.parseHost("10.0.0.-1")).isEmpty();
        assertThat(IpLiterals.parseHost("[fd00::1::2]")).isEmpty();
        assertThat(IpLiterals.parseHost("[" + "1:".repeat(30) + "1]")).isEmpty();
        assertThat(IpLiterals.parseHost("[:]")).isEmpty();
    }

    @Test
    void ambiguousUrlHostsNeverPass() {
        TargetPolicy policy = TargetPolicy.builder()
                .allowSuffix("internal")
                .allowCidr(Cidr.parse("10.0.0.0/8"))
                .allowPort(443)
                .build();

        for (String host : new String[] {"127.1", "2130706433", "0x7f.0.0.1", "0177.0.0.1", "10.1"}) {
            assertThat(policy.checkUrl(URI.create("https://" + host + "/")).isAllowed())
                    .as(host)
                    .isFalse();
        }
    }

    @Test
    void validatesHostNames() {
        assertThat(HostNames.normalize("Orders-1.Payments.Internal")).contains("orders-1.payments.internal");
        assertThat(HostNames.normalize("a.b-")).isEmpty();
        assertThat(HostNames.normalize("a b.internal")).isEmpty();
        assertThat(HostNames.normalize("x".repeat(254))).isEmpty();
    }

    private static InetAddress literal(String text) {
        return InetAddress.ofLiteral(text);
    }
}
