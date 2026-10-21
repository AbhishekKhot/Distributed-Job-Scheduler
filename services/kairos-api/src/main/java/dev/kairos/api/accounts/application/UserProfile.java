package dev.kairos.api.accounts.application;

import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A user's profile (LLD section 3.3, APIs 1 and 5).
 *
 * @param id the user id
 * @param email the normalised email
 * @param team the user's team, or {@code null} for a user without one
 * @param teamName the team's slug, or {@code null}
 * @param createdAt when the account was created
 */
public record UserProfile(
        UserId id,
        String email,
        @Nullable TeamId team,
        @Nullable String teamName,
        Instant createdAt) {

    /** Validates the components. */
    public UserProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Leaves the email out. */
    @Override
    public String toString() {
        return "UserProfile[id=" + id + ", team=" + team + ", createdAt=" + createdAt + "]";
    }
}
