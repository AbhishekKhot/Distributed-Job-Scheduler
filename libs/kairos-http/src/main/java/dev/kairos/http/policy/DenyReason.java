package dev.kairos.http.policy;

/**
 * Why the target policy refused a URL, host or address. Used in logs and metrics; the API reports every reason as
 * {@code target-not-allowed} without detail.
 */
public enum DenyReason {
    /** The URL is not absolute, has a fragment, or its authority cannot be read unambiguously. */
    MALFORMED_URL,
    /** The scheme is not {@code https}. */
    NOT_HTTPS,
    /** The URL carries user information ({@code user:password@host}). */
    USER_INFO,
    /** The host is not a valid DNS name (LDH labels, ASCII only, no trailing dot). */
    INVALID_HOST,
    /** The host looks like an IP literal but is not in a strict form ({@code 127.1}, {@code 0x7f.0.0.1}, ...). */
    AMBIGUOUS_IP_LITERAL,
    /** The host is on the fixed deny list (metadata, Kubernetes API, localhost, infrastructure). */
    DENIED_HOST,
    /** The host matches no allowed DNS suffix or exact host. */
    HOST_NOT_ALLOWED,
    /** The port is not an allowed port. */
    PORT_NOT_ALLOWED,
    /** An address is on the fixed deny list (loopback, link-local, metadata, multicast, infrastructure, ...). */
    DENIED_ADDRESS,
    /** An address lies outside every allowed CIDR. */
    ADDRESS_NOT_ALLOWED,
    /** The host resolved to no address. */
    NO_ADDRESS
}
