package dev.kairos.api.team.application;

import dev.kairos.domain.id.TeamId;
import java.util.Map;
import java.util.Objects;

/**
 * Writes the team feature's entries to {@code audit_log} (LLD section 2.2), in the caller's transaction. Details
 * never contain secrets, keys, tokens or emails.
 *
 * <p>Feature port adapted to the shared audit writer; existing use cases keep their typed event contracts.
 */
public interface TeamAudit {

    /** A user created a team (resource type {@code team}). */
    String TEAM_CREATED = "TEAM_CREATED";

    /** A member added a user to the team (resource type {@code user}). */
    String MEMBER_ADDED = "MEMBER_ADDED";

    /** A member removed another member (resource type {@code user}). */
    String MEMBER_REMOVED = "MEMBER_REMOVED";

    /** A member left the team (resource type {@code user}). */
    String MEMBER_LEFT = "MEMBER_LEFT";

    /** Every session of a removed or leaving member was revoked (resource type {@code user}), as in T-202. */
    String SESSIONS_REVOKED = "SESSIONS_REVOKED";

    /** Resource type of team entries. */
    String TEAM = "team";

    /** Resource type of member entries. */
    String USER = "user";

    /**
     * Records an entry.
     *
     * @param entry the entry
     */
    void record(Entry entry);

    /**
     * One audit entry.
     *
     * @param team the team the entry belongs to
     * @param actor {@code user:{id}}
     * @param action the action, for example {@link #TEAM_CREATED}
     * @param resourceType {@link #TEAM} or {@link #USER}
     * @param resourceId the team or user id
     * @param details non-secret details, numbers and fixed words only
     */
    record Entry(
            TeamId team,
            String actor,
            String action,
            String resourceType,
            String resourceId,
            Map<String, Object> details) {

        /** Validates the components and copies the details. */
        public Entry {
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(resourceType, "resourceType");
            Objects.requireNonNull(resourceId, "resourceId");
            details = Map.copyOf(details);
        }
    }
}
