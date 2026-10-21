package dev.kairos.api.accounts.application;

import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Writes the accounts feature's entries to {@code audit_log} (LLD section 2.2), in the caller's transaction when there
 * is one. Details never contain secrets, passwords, tokens or emails.
 */
public interface AccountAudit {

    /** Repeated login failures locked an account. */
    String LOGIN_LOCKED = "LOGIN_LOCKED";

    /** A user changed their password. */
    String PASSWORD_CHANGED = "PASSWORD_CHANGED";

    /** Several sessions of a user were revoked at once (logout of all other sessions, or refresh-token reuse). */
    String SESSIONS_REVOKED = "SESSIONS_REVOKED";

    /** The actor of entries written by the login lockout. */
    String LOCKOUT_ACTOR = "system:login-lockout";

    /** The actor of entries written when a reused refresh token revokes its family. */
    String REUSE_ACTOR = "system:refresh-reuse";

    /**
     * Records an entry.
     *
     * @param entry the entry
     */
    void record(Entry entry);

    /**
     * One audit entry about a user.
     *
     * @param team the user's team, or {@code null} for a user without one
     * @param actor {@code user:{id}} or {@code system:{task}}
     * @param action the action, for example {@link #PASSWORD_CHANGED}
     * @param user the user the entry is about (resource type {@code user})
     * @param details non-secret details, numbers and fixed words only
     */
    record Entry(@Nullable TeamId team, String actor, String action, UserId user, Map<String, Object> details) {

        /** Validates the components and copies the details. */
        public Entry {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(user, "user");
            details = Map.copyOf(details);
        }

        /**
         * Returns the actor value of a user.
         *
         * @param user the user
         * @return {@code user:{id}}
         */
        public static String userActor(UserId user) {
            return "user:" + user.value();
        }
    }
}
