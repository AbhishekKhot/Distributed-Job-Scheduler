package dev.kairos.http.policy;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The platform-wide target policy (HLD section 11): which URLs, host names, ports and addresses a job may call.
 *
 * <p>A target is allowed only when every rule passes:
 *
 * <ol>
 *   <li>the URL is absolute {@code https} without user information or fragment, and its authority reads the same to
 *       every parser;
 *   <li>the port is an allowed port (443 when the URL has none);
 *   <li>the host is a valid DNS name that equals an allowed host or ends with an allowed suffix, or a strict IP
 *       literal (ambiguous literals such as {@code 127.1} are refused);
 *   <li>every address the host resolves to lies in an allowed CIDR.
 * </ol>
 *
 * <p>A fixed deny list overrides every allow rule and cannot be configured away: loopback, unspecified, link-local
 * (including the metadata address {@code 169.254.169.254}), multicast, reserved and broadcast ranges, IPv6 forms that
 * embed an IPv4 address the policy cannot see (IPv4-compatible, NAT64, 6to4, Teredo), the metadata and Kubernetes API
 * host names, {@code localhost}, and the infrastructure hosts and addresses given at startup (the Kubernetes API
 * service address, databases, Kafka, Redis). IPv4-mapped IPv6 addresses are folded to IPv4 before any rule runs.
 *
 * <p>Immutable and thread-safe.
 */
public final class TargetPolicy {

    /** The only scheme a target may use. */
    public static final String HTTPS = "https";

    private static final int DEFAULT_HTTPS_PORT = 443;
    private static final int MAX_URL = 2048;

    /** Ranges no configuration can allow. */
    private static final List<Cidr> FIXED_DENIED_CIDRS = List.of(
            Cidr.parse("0.0.0.0/8"),
            Cidr.parse("127.0.0.0/8"),
            Cidr.parse("169.254.0.0/16"),
            Cidr.parse("224.0.0.0/4"),
            Cidr.parse("240.0.0.0/4"),
            // Unspecified, loopback and the deprecated IPv4-compatible addresses (::a.b.c.d).
            Cidr.parse("::/96"),
            Cidr.parse("fe80::/10"),
            Cidr.parse("fec0::/10"),
            Cidr.parse("ff00::/8"),
            // Translation and tunnelling prefixes embed an IPv4 address that would bypass the IPv4 rules.
            Cidr.parse("64:ff9b::/96"),
            Cidr.parse("64:ff9b:1::/48"),
            Cidr.parse("2002::/16"),
            Cidr.parse("2001::/32"),
            // Instance metadata over IPv6 on other clouds; harmless to deny everywhere.
            Cidr.parse("fd00:ec2::254"));

    /** Host names no configuration can allow. */
    private static final Set<String> FIXED_DENIED_NAMES = Set.of(
            "localhost",
            "metadata",
            "metadata.google.internal",
            "metadata.goog",
            "instance-data",
            "instance-data.ec2.internal",
            "kubernetes",
            "kubernetes.default",
            "kubernetes.default.svc");

    /** Name suffixes no configuration can allow: any {@code *.localhost} and the API service in any cluster domain. */
    private static final List<String> FIXED_DENIED_SUFFIXES = List.of(".localhost", ".metadata.google.internal");

    private static final String KUBERNETES_SERVICE_PREFIX = "kubernetes.default.svc.";

    private final List<String> allowedSuffixes;
    private final Set<String> allowedHosts;
    private final List<Cidr> allowedCidrs;
    private final Set<Integer> allowedPorts;
    private final Set<String> deniedHosts;
    private final List<Cidr> deniedCidrs;

    private TargetPolicy(Builder builder) {
        this.allowedSuffixes = List.copyOf(builder.allowedSuffixes);
        this.allowedHosts = Set.copyOf(builder.allowedHosts);
        this.allowedCidrs = List.copyOf(builder.allowedCidrs);
        this.allowedPorts = Set.copyOf(builder.allowedPorts);
        this.deniedHosts = Set.copyOf(builder.deniedHosts);
        List<Cidr> denied = new ArrayList<>(FIXED_DENIED_CIDRS);
        denied.addAll(builder.deniedCidrs);
        this.deniedCidrs = List.copyOf(denied);
    }

    /**
     * Starts a policy. With no allow rules the policy refuses everything.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Checks a target URL: scheme, authority, port and host name. A host name's addresses are checked separately at
     * connect time ({@link #checkAddresses}); an IP literal is checked here.
     *
     * @param url the URL
     * @return the decision, with the normalised host and effective port when allowed
     */
    public TargetDecision checkUrl(URI url) {
        Objects.requireNonNull(url, "url");
        if (!url.isAbsolute()
                || url.isOpaque()
                || url.getRawFragment() != null
                || url.toString().length() > MAX_URL) {
            return new TargetDecision.Denied(DenyReason.MALFORMED_URL);
        }
        String scheme = url.getScheme();
        if (scheme == null || !HTTPS.equals(scheme.toLowerCase(Locale.ROOT))) {
            return new TargetDecision.Denied(DenyReason.NOT_HTTPS);
        }
        if (url.getRawUserInfo() != null) {
            return new TargetDecision.Denied(DenyReason.USER_INFO);
        }
        Authority authority = Authority.parse(url.getRawAuthority());
        // The authority must read the same to java.net.URI and to this parser; any disagreement (percent-encoding,
        // stray characters) is a parser differential an attacker could use to aim checks and connection apart.
        if (authority == null
                || url.getHost() == null
                || !url.getHost().equalsIgnoreCase(authority.host())
                || url.getPort() != authority.port()) {
            return new TargetDecision.Denied(DenyReason.MALFORMED_URL);
        }
        return checkHost(authority.host(), authority.port() < 0 ? DEFAULT_HTTPS_PORT : authority.port());
    }

    /**
     * Checks a host and port; an IP literal host is also checked against the address rules.
     *
     * @param host the host as written in the URL ({@code [..]} around IPv6 literals)
     * @param port the port
     * @return the decision
     */
    public TargetDecision checkHost(String host, int port) {
        if (!allowedPorts.contains(port)) {
            return new TargetDecision.Denied(DenyReason.PORT_NOT_ALLOWED);
        }
        Optional<DenyReason> nameDenied = checkHostName(host);
        if (nameDenied.isPresent()) {
            return new TargetDecision.Denied(nameDenied.get());
        }
        return new TargetDecision.Allowed(host.toLowerCase(Locale.ROOT), port);
    }

    /**
     * Checks a host without its port: the name rules for a DNS name, or the address rules for an IP literal.
     *
     * @param host the host as written in the URL
     * @return the reason the host is refused, or empty if it is allowed
     */
    public Optional<DenyReason> checkHostName(String host) {
        Objects.requireNonNull(host, "host");
        if (IpLiterals.looksLikeLiteral(host)) {
            Optional<InetAddress> literal = IpLiterals.parseHost(host);
            if (literal.isEmpty()) {
                return Optional.of(DenyReason.AMBIGUOUS_IP_LITERAL);
            }
            return checkAddress(literal.get());
        }
        Optional<String> normalized = HostNames.normalize(host);
        if (normalized.isEmpty()) {
            return Optional.of(DenyReason.INVALID_HOST);
        }
        String name = normalized.get();
        if (isDeniedName(name)) {
            return Optional.of(DenyReason.DENIED_HOST);
        }
        if (allowedHosts.contains(name) || allowedSuffixes.stream().anyMatch(name::endsWith)) {
            return Optional.empty();
        }
        return Optional.of(DenyReason.HOST_NOT_ALLOWED);
    }

    /**
     * Checks one resolved address: the fixed deny list first, then the allowed CIDRs.
     *
     * @param address the address
     * @return the reason the address is refused, or empty if it is allowed
     */
    public Optional<DenyReason> checkAddress(InetAddress address) {
        InetAddress canonical = IpLiterals.canonical(Objects.requireNonNull(address, "address"));
        if (canonical.isAnyLocalAddress()
                || canonical.isLoopbackAddress()
                || canonical.isLinkLocalAddress()
                || canonical.isMulticastAddress()
                || deniedCidrs.stream().anyMatch(cidr -> cidr.contains(canonical))) {
            return Optional.of(DenyReason.DENIED_ADDRESS);
        }
        if (allowedCidrs.stream().noneMatch(cidr -> cidr.contains(canonical))) {
            return Optional.of(DenyReason.ADDRESS_NOT_ALLOWED);
        }
        return Optional.empty();
    }

    /**
     * Checks every address a host resolved to. One refused address refuses the host: a name that resolves to both a
     * public and an internal address is a rebinding attempt, not a choice.
     *
     * @param addresses the resolved addresses
     * @return the first reason an address is refused, or empty if all are allowed
     */
    public Optional<DenyReason> checkAddresses(Collection<InetAddress> addresses) {
        if (addresses.isEmpty()) {
            return Optional.of(DenyReason.NO_ADDRESS);
        }
        for (InetAddress address : addresses) {
            Optional<DenyReason> denied = checkAddress(address);
            if (denied.isPresent()) {
                return denied;
            }
        }
        return Optional.empty();
    }

    private boolean isDeniedName(String name) {
        return FIXED_DENIED_NAMES.contains(name)
                || deniedHosts.contains(name)
                || name.startsWith(KUBERNETES_SERVICE_PREFIX)
                || FIXED_DENIED_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    /** Builds a {@link TargetPolicy}. Not thread-safe. */
    public static final class Builder {

        private final Set<String> allowedSuffixes = new LinkedHashSet<>();
        private final Set<String> allowedHosts = new LinkedHashSet<>();
        private final Set<Cidr> allowedCidrs = new LinkedHashSet<>();
        private final Set<Integer> allowedPorts = new LinkedHashSet<>();
        private final Set<String> deniedHosts = new LinkedHashSet<>();
        private final Set<Cidr> deniedCidrs = new LinkedHashSet<>();

        private Builder() {}

        /**
         * Allows host names ending with a suffix, for example {@code .svc.cluster.local}. The leading dot is implied,
         * so {@code internal} allows {@code orders.internal} but not {@code internal} itself.
         *
         * @param suffix the suffix
         * @return this builder
         * @throws IllegalArgumentException if the suffix is not a valid DNS name
         */
        public Builder allowSuffix(String suffix) {
            String bare = suffix.startsWith(".") ? suffix.substring(1) : suffix;
            allowedSuffixes.add("." + requireName(bare, "suffix"));
            return this;
        }

        /**
         * Allows one exact host name.
         *
         * @param host the host name
         * @return this builder
         * @throws IllegalArgumentException if the host is not a valid DNS name
         */
        public Builder allowHost(String host) {
            allowedHosts.add(requireName(host, "host"));
            return this;
        }

        /**
         * Allows addresses in a network. Ranges on the fixed deny list stay denied.
         *
         * @param cidr the network
         * @return this builder
         */
        public Builder allowCidr(Cidr cidr) {
            allowedCidrs.add(Objects.requireNonNull(cidr, "cidr"));
            return this;
        }

        /**
         * Allows a port.
         *
         * @param port 1 to 65535
         * @return this builder
         * @throws IllegalArgumentException if the port is out of range
         */
        public Builder allowPort(int port) {
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("a port is 1 to 65535");
            }
            allowedPorts.add(port);
            return this;
        }

        /**
         * Denies an infrastructure host name in addition to the fixed list.
         *
         * @param host the host name
         * @return this builder
         * @throws IllegalArgumentException if the host is not a valid DNS name
         */
        public Builder denyHost(String host) {
            deniedHosts.add(requireName(host, "host"));
            return this;
        }

        /**
         * Denies an infrastructure network in addition to the fixed list.
         *
         * @param cidr the network
         * @return this builder
         */
        public Builder denyCidr(Cidr cidr) {
            deniedCidrs.add(Objects.requireNonNull(cidr, "cidr"));
            return this;
        }

        /**
         * Denies an infrastructure host given either as an address or as a host name. Names are denied by name; the
         * caller also denies the addresses it resolved at startup.
         *
         * @param hostOrAddress the host name or address
         * @return this builder
         * @throws IllegalArgumentException if it is neither a strict address nor a valid host name
         */
        public Builder denyInfrastructure(String hostOrAddress) {
            @Nullable InetAddress address = IpLiterals.parseAddress(hostOrAddress).orElse(null);
            if (address != null) {
                return denyCidr(Cidr.host(address));
            }
            return denyHost(hostOrAddress);
        }

        /**
         * Builds the policy.
         *
         * @return the policy
         */
        public TargetPolicy build() {
            return new TargetPolicy(this);
        }

        private static String requireName(String host, String what) {
            return HostNames.normalize(Objects.requireNonNull(host, what))
                    .orElseThrow(() -> new IllegalArgumentException("not a valid DNS " + what + ": " + host));
        }
    }

    /** The host and port of a URL authority, read without trusting {@link URI}'s lenient parsing. */
    private record Authority(String host, int port) {

        static @Nullable Authority parse(@Nullable String raw) {
            if (raw == null || raw.isEmpty() || raw.indexOf('%') >= 0 || raw.indexOf('@') >= 0) {
                return null;
            }
            String host;
            String rest;
            if (raw.charAt(0) == '[') {
                int close = raw.indexOf(']');
                if (close < 0) {
                    return null;
                }
                host = raw.substring(0, close + 1);
                rest = raw.substring(close + 1);
            } else {
                int colon = raw.indexOf(':');
                host = colon < 0 ? raw : raw.substring(0, colon);
                rest = colon < 0 ? "" : raw.substring(colon);
            }
            if (host.isEmpty()) {
                return null;
            }
            if (rest.isEmpty()) {
                return new Authority(host, -1);
            }
            if (rest.charAt(0) != ':' || rest.length() < 2 || rest.length() > 6) {
                return null;
            }
            int port = 0;
            for (int i = 1; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c < '0' || c > '9') {
                    return null;
                }
                port = port * 10 + (c - '0');
            }
            return port >= 1 && port <= 65_535 ? new Authority(host, port) : null;
        }
    }
}
