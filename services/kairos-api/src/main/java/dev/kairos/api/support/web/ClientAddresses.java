package dev.kairos.api.support.web;

import com.google.common.net.InetAddresses;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Finds the client address of a request for per-IP rate limits.
 *
 * <p>{@code X-Forwarded-For} is client-controlled, so it is read only when the direct peer is a trusted proxy (one of
 * the configured CIDRs, for example the load balancer's range). The header is then walked from the right, skipping
 * trusted proxies, and the first untrusted address is the client. An entry that is not an IP literal stops the walk
 * at the last trusted hop. Literals are parsed without DNS lookups. With no trusted proxies configured the header is
 * ignored entirely. Immutable and thread-safe.
 */
public final class ClientAddresses {

    /** The forwarding header. */
    public static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final int MAX_HOPS = 20;
    private static final int MAX_HEADER_LENGTH = 2048;

    private final List<Block> trusted;

    /**
     * Creates the resolver.
     *
     * @param trustedProxies CIDRs of trusted proxies, for example {@code 10.0.0.0/8}
     * @throws IllegalArgumentException if a CIDR is malformed
     */
    public ClientAddresses(List<String> trustedProxies) {
        List<Block> blocks = new ArrayList<>();
        for (String cidr : trustedProxies) {
            blocks.add(Block.parse(cidr));
        }
        this.trusted = List.copyOf(blocks);
    }

    /**
     * Returns the client address of a request.
     *
     * @param request the request
     * @return the client's IP address in canonical text form
     */
    public String clientAddress(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        InetAddress peerAddress = parse(peer);
        if (peerAddress == null) {
            return Objects.requireNonNullElse(peer, "unknown");
        }
        if (!isTrusted(peerAddress)) {
            return InetAddresses.toAddrString(peerAddress);
        }
        List<String> hops = forwardedHops(request);
        InetAddress client = peerAddress;
        for (int i = hops.size() - 1; i >= 0 && hops.size() - i <= MAX_HOPS; i--) {
            InetAddress hop = parse(hops.get(i));
            if (hop == null) {
                break;
            }
            client = hop;
            if (!isTrusted(hop)) {
                break;
            }
        }
        return InetAddresses.toAddrString(client);
    }

    private boolean isTrusted(InetAddress address) {
        for (Block block : trusted) {
            if (block.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> forwardedHops(HttpServletRequest request) {
        List<String> hops = new ArrayList<>();
        int length = 0;
        for (String header : Collections.list(request.getHeaders(FORWARDED_FOR))) {
            length += header.length();
            if (length > MAX_HEADER_LENGTH) {
                // An oversized header is not trusted at all: the peer (a trusted proxy) is reported instead.
                return List.of();
            }
            for (String hop : header.split(",", -1)) {
                hops.add(hop.strip());
            }
        }
        return hops;
    }

    private static @Nullable InetAddress parse(@Nullable String literal) {
        if (literal == null || literal.isEmpty() || literal.length() > 64 || !InetAddresses.isInetAddress(literal)) {
            return null;
        }
        return InetAddresses.forString(literal);
    }

    /** One CIDR block. */
    private record Block(byte[] network, int prefix) {

        static Block parse(String cidr) {
            int slash = cidr.indexOf('/');
            String address = slash < 0 ? cidr : cidr.substring(0, slash);
            if (!InetAddresses.isInetAddress(address)) {
                throw new IllegalArgumentException("Not a CIDR: " + cidr);
            }
            byte[] network = InetAddresses.forString(address).getAddress();
            int prefix;
            try {
                prefix = slash < 0 ? network.length * 8 : Integer.parseInt(cidr.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not a CIDR: " + cidr, e);
            }
            if (prefix < 0 || prefix > network.length * 8) {
                throw new IllegalArgumentException("Not a CIDR: " + cidr);
            }
            return new Block(network, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int bits = prefix;
            for (int i = 0; i < network.length && bits > 0; i++, bits -= 8) {
                int mask = bits >= 8 ? 0xFF : (0xFF << (8 - bits)) & 0xFF;
                if ((candidate[i] & mask) != (network[i] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
