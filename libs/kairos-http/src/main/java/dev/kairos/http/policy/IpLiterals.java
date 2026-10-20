package dev.kairos.http.policy;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * Strict parsing of IP address literals.
 *
 * <p>The JDK and many URL parsers accept legacy IPv4 forms ({@code 127.1}, {@code 2130706433}, {@code 0x7f.0.0.1},
 * {@code 0177.0.0.1}) that different components read differently. Kairos accepts only the canonical dotted quad
 * (four decimal parts, no leading zeros) and bracketed IPv6 without a zone id; any other host whose last label starts
 * with a digit is treated as an ambiguous literal and refused. IPv4-mapped IPv6 addresses are folded to IPv4 so the
 * IPv4 rules apply to them.
 *
 * <p>Stateless and thread-safe.
 */
public final class IpLiterals {

    private static final int MAX_IPV6_LITERAL = 45;

    private IpLiterals() {}

    /**
     * Tells whether a URL host is meant as an IP literal: it is bracketed, contains a colon, or its last label starts
     * with a digit (no top-level domain does).
     *
     * @param host the host as written in the URL
     * @return true if the host must parse as a strict literal
     */
    public static boolean looksLikeLiteral(String host) {
        if (host.isEmpty()) {
            return false;
        }
        if (host.charAt(0) == '[' || host.indexOf(':') >= 0) {
            return true;
        }
        int lastDot = host.lastIndexOf('.');
        String lastLabel = lastDot < 0 ? host : host.substring(lastDot + 1);
        return !lastLabel.isEmpty() && isAsciiDigit(lastLabel.charAt(0));
    }

    /**
     * Parses a URL host literal: a canonical dotted-quad IPv4 address or an IPv6 address, bracketed as in a URL or
     * bare as HttpClient passes it to the resolver.
     *
     * @param host the host, for example {@code 10.1.2.3}, {@code [fd00::1]} or {@code fd00::1}
     * @return the address (IPv4-mapped IPv6 folded to IPv4), or empty if the literal is not in a strict form
     */
    public static Optional<InetAddress> parseHost(String host) {
        if (host.length() > 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return parseIpv6(host.substring(1, host.length() - 1));
        }
        return parseAddress(host);
    }

    /**
     * Parses an address as written in configuration: a canonical dotted quad or an unbracketed IPv6 address.
     *
     * @param text the address
     * @return the address (IPv4-mapped IPv6 folded to IPv4), or empty if it is not in a strict form
     */
    public static Optional<InetAddress> parseAddress(String text) {
        return text.indexOf(':') >= 0 ? parseIpv6(text) : parseIpv4(text);
    }

    /**
     * Folds an IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) to its IPv4 address; other addresses are returned
     * unchanged.
     *
     * @param address the address
     * @return the canonical address
     */
    public static InetAddress canonical(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            if (isMapped(bytes)) {
                byte[] v4 = {bytes[12], bytes[13], bytes[14], bytes[15]};
                return byAddress(v4);
            }
        }
        return address;
    }

    /**
     * Creates an address from raw bytes without any name lookup.
     *
     * @param bytes 4 or 16 bytes
     * @return the address
     * @throws IllegalArgumentException if the length is not 4 or 16
     */
    static InetAddress byAddress(byte[] bytes) {
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("an address has 4 or 16 bytes", e);
        }
    }

    private static Optional<InetAddress> parseIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.charAt(0) == '0')) {
                return Optional.empty();
            }
            int value = 0;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (!isAsciiDigit(c)) {
                    return Optional.empty();
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return Optional.empty();
            }
            bytes[i] = (byte) value;
        }
        return Optional.of(byAddress(bytes));
    }

    private static Optional<InetAddress> parseIpv6(String text) {
        if (text.length() < 2 || text.length() > MAX_IPV6_LITERAL || text.indexOf(':') < 0) {
            return Optional.empty();
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // No zone ids ('%'), no brackets inside, nothing but hex digits, colons and an embedded dotted quad.
            if (!(isAsciiDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == ':' || c == '.')) {
                return Optional.empty();
            }
        }
        if (text.indexOf('.') >= 0
                && parseIpv4(text.substring(text.lastIndexOf(':') + 1)).isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(canonical(InetAddress.ofLiteral(text)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean isMapped(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
