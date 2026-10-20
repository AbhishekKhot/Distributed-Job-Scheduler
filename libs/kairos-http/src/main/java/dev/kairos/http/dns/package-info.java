/**
 * Name resolution for outbound calls.
 *
 * <p>{@link dev.kairos.http.dns.ValidatingDnsResolver} is the only resolver the HTTP client uses: it checks every
 * address against the target policy and hands the connection exactly the addresses it checked, which blocks DNS
 * rebinding. {@link dev.kairos.http.dns.HostLookup} is the underlying lookup.
 */
package dev.kairos.http.dns;
