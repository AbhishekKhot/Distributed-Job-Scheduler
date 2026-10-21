package dev.kairos.api.support.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressesTest {

    private final ClientAddresses behindProxies = new ClientAddresses(List.of("10.0.0.0/8", "fd00::/8"));

    @Test
    void theHeaderIsIgnoredWithoutTrustedProxies() {
        MockHttpServletRequest request = request("10.1.2.3", "198.51.100.7");

        assertThat(new ClientAddresses(List.of()).clientAddress(request)).isEqualTo("10.1.2.3");
    }

    @Test
    void theHeaderIsIgnoredFromAnUntrustedPeer() {
        assertThat(behindProxies.clientAddress(request("203.0.113.5", "198.51.100.7")))
                .isEqualTo("203.0.113.5");
    }

    @Test
    void theRightmostUntrustedHopIsTheClient() {
        assertThat(behindProxies.clientAddress(request("10.0.0.1", "1.1.1.1, 198.51.100.7, 10.0.0.9")))
                .isEqualTo("198.51.100.7");
        assertThat(behindProxies.clientAddress(request("10.0.0.1", "198.51.100.7", "10.0.0.9")))
                .isEqualTo("198.51.100.7");
    }

    @Test
    void aNonLiteralHopStopsAtTheLastTrustedProxy() {
        assertThat(behindProxies.clientAddress(request("10.0.0.1", "evil.example.com, 10.0.0.9")))
                .isEqualTo("10.0.0.9");
        assertThat(behindProxies.clientAddress(request("10.0.0.1", "x".repeat(3000))))
                .isEqualTo("10.0.0.1");
    }

    @Test
    void allTrustedHopsGiveTheLeftmost() {
        assertThat(behindProxies.clientAddress(request("10.0.0.1", "10.0.0.7, 10.0.0.9")))
                .isEqualTo("10.0.0.7");
    }

    @Test
    void ipv6IsSupported() {
        assertThat(behindProxies.clientAddress(request("fd00::1", "2001:db8::1")))
                .isEqualTo("2001:db8::1");
    }

    @Test
    void malformedCidrsAreRejected() {
        for (String cidr : List.of("10.0.0.0/33", "not-an-ip/8", "10.0.0.0/x", "::/129")) {
            assertThatThrownBy(() -> new ClientAddresses(List.of(cidr))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new ClientAddresses(List.of("203.0.113.5")).clientAddress(request("203.0.113.5", "198.51.100.1")))
                .isEqualTo("198.51.100.1");
    }

    private static MockHttpServletRequest request(String peer, String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/auth/login");
        request.setRemoteAddr(peer);
        for (String header : forwardedFor) {
            request.addHeader(ClientAddresses.FORWARDED_FOR, header);
        }
        return request;
    }
}
