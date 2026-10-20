package dev.kairos.http.dns;

import dev.kairos.http.policy.DenyReason;
import dev.kairos.http.policy.IpLiterals;
import dev.kairos.http.policy.TargetDecision;
import dev.kairos.http.policy.TargetNotAllowedException;
import dev.kairos.http.policy.TargetPolicy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.apache.hc.client5.http.DnsResolver;

/**
 * The resolver Apache HttpClient connects through: it resolves a host, checks every address against the
 * {@link TargetPolicy} and returns only those checked addresses, so the socket connects to exactly the IP that passed
 * the check. A second resolution cannot sneak in between the check and the connect, which defeats DNS rebinding.
 *
 * <p>The returned addresses carry the original host name, so TLS still sends that name in SNI and verifies the
 * certificate against it. Reverse lookups are never made. If any resolved address is refused, the whole host is refused
 * with {@link TargetNotAllowedException}; a resolution failure is reported as a plain {@link UnknownHostException}.
 *
 * <p>Thread-safe.
 */
public final class ValidatingDnsResolver implements DnsResolver {

    private final TargetPolicy policy;
    private final HostLookup lookup;

    /**
     * Creates the resolver.
     *
     * @param policy the target policy
     * @param lookup the underlying name resolution
     */
    public ValidatingDnsResolver(TargetPolicy policy, HostLookup lookup) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /**
     * Resolves a host and checks its name and every address.
     *
     * @param host the host
     * @return the checked addresses
     * @throws TargetNotAllowedException if the name or any address is refused
     * @throws UnknownHostException if the name cannot be resolved
     */
    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return resolveChecked(host).toArray(InetAddress[]::new);
    }

    /**
     * Resolves a host and checks its name, the port and every address.
     *
     * @param host the host
     * @param port the port
     * @return the checked socket addresses
     * @throws TargetNotAllowedException if the name, port or any address is refused
     * @throws UnknownHostException if the name cannot be resolved
     */
    @Override
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
        if (policy.checkHost(host, port) instanceof TargetDecision.Denied denied) {
            throw new TargetNotAllowedException(denied.reason());
        }
        List<InetSocketAddress> result = new ArrayList<>();
        for (InetAddress address : resolveChecked(host)) {
            result.add(new InetSocketAddress(address, port));
        }
        return List.copyOf(result);
    }

    /**
     * Returns the host unchanged after checking it: Kairos never makes reverse lookups.
     *
     * @param host the host
     * @return the host
     * @throws TargetNotAllowedException if the host is refused
     */
    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        Optional<DenyReason> denied = policy.checkHostName(host);
        if (denied.isPresent()) {
            throw new TargetNotAllowedException(denied.get());
        }
        return host;
    }

    private List<InetAddress> resolveChecked(String host) throws UnknownHostException {
        Objects.requireNonNull(host, "host");
        Optional<DenyReason> nameDenied = policy.checkHostName(host);
        if (nameDenied.isPresent()) {
            throw new TargetNotAllowedException(nameDenied.get());
        }
        Optional<InetAddress> literal =
                IpLiterals.looksLikeLiteral(host) ? IpLiterals.parseHost(host) : Optional.empty();
        String name = host.toLowerCase(Locale.ROOT);
        List<InetAddress> resolved = literal.isPresent() ? List.of(literal.get()) : lookup.lookup(name);
        // Copy before checking, so a lookup cannot hand over a list or address it later changes.
        List<InetAddress> checked = new ArrayList<>(resolved.size());
        for (InetAddress address : resolved) {
            InetAddress canonical = IpLiterals.canonical(address);
            checked.add(InetAddress.getByAddress(name, canonical.getAddress()));
        }
        Optional<DenyReason> addressDenied = policy.checkAddresses(checked);
        if (addressDenied.isPresent()) {
            throw new TargetNotAllowedException(addressDenied.get());
        }
        return List.copyOf(checked);
    }
}
