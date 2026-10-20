package dev.kairos.http.policy;

import java.util.Objects;

/** The target policy's verdict on a URL, host or address. */
public sealed interface TargetDecision {

    /**
     * Tells whether the target may be called.
     *
     * @return true for {@link Allowed}
     */
    default boolean isAllowed() {
        return this instanceof Allowed;
    }

    /**
     * The target may be called.
     *
     * @param host the normalised host: a lower-case DNS name, or an IP literal as written in the URL
     * @param port the port, defaulted to 443 when the URL has none
     */
    record Allowed(String host, int port) implements TargetDecision {

        /** Validates the components. */
        public Allowed {
            Objects.requireNonNull(host, "host");
        }
    }

    /**
     * The target must not be called.
     *
     * @param reason why
     */
    record Denied(DenyReason reason) implements TargetDecision {

        /** Validates the reason. */
        public Denied {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
