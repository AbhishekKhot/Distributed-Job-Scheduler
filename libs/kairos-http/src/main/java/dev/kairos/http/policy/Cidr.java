package dev.kairos.http.policy;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An IPv4 or IPv6 network in CIDR notation, for example {@code 10.0.0.0/8} or {@code fc00::/7}.
 *
 * <p>Parsing is strict: the address must be a canonical literal (see {@link IpLiterals}), the prefix must fit the
 * address family, and no host bits may be set, so a typo cannot silently widen or narrow a network. Immutable and
 * thread-safe.
 */
public final class Cidr {

    private static final int MAX_TEXT = 64;

    private final byte[] network;
    private final int prefixLength;

    private Cidr(byte[] network, int prefixLength) {
        this.network = network;
        this.prefixLength = prefixLength;
    }

    /**
     * Parses a network; a bare address means a single host ({@code /32} or {@code /128}).
     *
     * @param text the network, for example {@code 172.16.0.0/12}
     * @return the network
     * @throws IllegalArgumentException if the text is not a strict CIDR or has host bits set
     */
    public static Cidr parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty() || text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("a CIDR is 1 to " + MAX_TEXT + " characters");
        }
        int slash = text.indexOf('/');
        String addressText = slash < 0 ? text : text.substring(0, slash);
        InetAddress address = IpLiterals.parseAddress(addressText)
                .orElseThrow(() -> new IllegalArgumentException("not a canonical IP address in CIDR: " + text));
        byte[] bytes = address.getAddress();
        int maxPrefix = bytes.length * 8;
        int prefix = slash < 0 ? maxPrefix : parsePrefix(text.substring(slash + 1), maxPrefix, text);
        for (int bit = prefix; bit < maxPrefix; bit++) {
            if ((bytes[bit / 8] & (0x80 >>> (bit % 8))) != 0) {
                throw new IllegalArgumentException("host bits are set in CIDR: " + text);
            }
        }
        return new Cidr(bytes, prefix);
    }

    /**
     * Returns a single-host network.
     *
     * @param address the address (IPv4-mapped IPv6 is folded to IPv4)
     * @return the network of that one address
     */
    public static Cidr host(InetAddress address) {
        byte[] bytes = IpLiterals.canonical(address).getAddress();
        return new Cidr(bytes, bytes.length * 8);
    }

    /**
     * Tells whether the address lies in this network. Addresses of the other family never match; IPv4-mapped IPv6 is
     * folded to IPv4 first.
     *
     * @param address the address
     * @return true if the address is in the network
     */
    public boolean contains(InetAddress address) {
        byte[] candidate = IpLiterals.canonical(address).getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xff << (8 - remainingBits)) & 0xff;
        return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    /**
     * Returns the prefix length.
     *
     * @return 0 to 32 for IPv4, 0 to 128 for IPv6
     */
    public int prefixLength() {
        return prefixLength;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof Cidr that && prefixLength == that.prefixLength && Arrays.equals(network, that.network);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(network) + prefixLength;
    }

    /** Returns the CIDR text, for example {@code 10.0.0.0/8}. */
    @Override
    public String toString() {
        return IpLiterals.byAddress(network.clone()).getHostAddress() + "/" + prefixLength;
    }

    private static int parsePrefix(String text, int maxPrefix, String cidr) {
        if (text.isEmpty() || text.length() > 3) {
            throw new IllegalArgumentException("invalid prefix length in CIDR: " + cidr);
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("invalid prefix length in CIDR: " + cidr);
            }
            value = value * 10 + (c - '0');
        }
        if (value > maxPrefix) {
            throw new IllegalArgumentException("prefix length too long in CIDR: " + cidr);
        }
        return value;
    }
}
