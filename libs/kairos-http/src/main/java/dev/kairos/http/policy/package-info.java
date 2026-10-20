/**
 * The platform target policy (HLD section 11).
 *
 * <p>{@link dev.kairos.http.policy.TargetPolicy} decides whether a URL, host name, port or resolved address may be
 * called; its fixed deny list (loopback, link-local and metadata, multicast, the Kubernetes API and infrastructure)
 * overrides every configured allow rule. {@link dev.kairos.http.policy.IpLiterals},
 * {@link dev.kairos.http.policy.HostNames} and {@link dev.kairos.http.policy.Cidr} parse hosts and networks strictly,
 * so no ambiguous form can be read one way by the check and another way by the connection.
 */
package dev.kairos.http.policy;
