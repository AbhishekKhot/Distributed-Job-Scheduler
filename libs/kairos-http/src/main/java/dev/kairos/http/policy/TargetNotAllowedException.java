package dev.kairos.http.policy;

import java.io.Serial;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * Thrown when the target policy refuses a host or one of its addresses.
 *
 * <p>It extends {@link UnknownHostException} because Apache HttpClient's resolver contract may only throw that type;
 * callers tell a refusal from a DNS failure by this subclass. The message names the reason only, never the host or the
 * addresses.
 */
public final class TargetNotAllowedException extends UnknownHostException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Why the target was refused. */
    private final DenyReason reason;

    /**
     * Creates the exception.
     *
     * @param reason why the target was refused
     */
    public TargetNotAllowedException(DenyReason reason) {
        super("target not allowed: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    /**
     * Returns why the target was refused.
     *
     * @return the reason
     */
    public DenyReason reason() {
        return reason;
    }
}
