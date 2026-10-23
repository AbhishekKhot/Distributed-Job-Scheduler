package dev.kairos.api.audit.application;

import dev.kairos.domain.id.TeamId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Writes security events on the caller's control-database connection. Invoke inside the mutation's transaction:
 * this port never starts or commits a separate transaction. Supply metadata only, never request bodies or secrets.
 */
public interface AuditWriter {
    void record(Entry entry);

    /** Validated metadata; a null team supports account events before onboarding. */
    record Entry(
            @Nullable TeamId team,
            String actor,
            String action,
            @Nullable String resourceType,
            @Nullable String resourceId,
            Map<String, Object> details) {
        private static final Set<String> SENSITIVE = Set.of(
                "password",
                "passwordhash",
                "secret",
                "secretvalue",
                "token",
                "accesstoken",
                "refreshtoken",
                "apikey",
                "signingkey",
                "authorization",
                "ciphertext");

        public Entry {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(action, "action");
            if (actor.length() > 160 || !actor.matches("^(user|key|operator|system):[A-Za-z0-9_.:-]+$")) {
                throw new IllegalArgumentException("invalid audit actor");
            }
            if (action.length() > 64 || !action.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
                throw new IllegalArgumentException("invalid audit action");
            }
            if ((resourceType != null && resourceType.length() > 64)
                    || (resourceId != null && resourceId.length() > 128)
                    || details.size() > 32) {
                throw new IllegalArgumentException("audit metadata is too large");
            }
            details.forEach((key, value) -> {
                if (key.length() > 64
                        || !key.matches("^[A-Za-z][A-Za-z0-9]{0,63}$")
                        || SENSITIVE.contains(key.toLowerCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("invalid audit detail name");
                }
                if (!(value instanceof String
                                || value instanceof Boolean
                                || value instanceof Integer
                                || value instanceof Long
                                || value instanceof Short)
                        || (value instanceof String text && text.length() > 1024)) {
                    throw new IllegalArgumentException("audit details must be bounded scalar metadata");
                }
            });
            details = Map.copyOf(details);
        }

        @Override
        public String toString() {
            return "AuditEntry[action=" + action + ", details=<redacted>]";
        }
    }
}
