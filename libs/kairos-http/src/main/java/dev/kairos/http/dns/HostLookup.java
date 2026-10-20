package dev.kairos.http.dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Resolves a DNS name to addresses. The result is never trusted: {@link ValidatingDnsResolver} checks every address
 * against the target policy before a connection may use it.
 */
@FunctionalInterface
public interface HostLookup {

    /**
     * Resolves a host name.
     *
     * @param host a validated, lower-case DNS name (never an IP literal)
     * @return the addresses, possibly empty
     * @throws UnknownHostException if the name cannot be resolved
     */
    List<InetAddress> lookup(String host) throws UnknownHostException;

    /**
     * Returns the JVM's resolver.
     *
     * @return the system lookup
     */
    static HostLookup system() {
        return host -> List.of(InetAddress.getAllByName(host));
    }
}
