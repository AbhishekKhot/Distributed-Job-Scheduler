package dev.kairos.api.team.application;

import dev.kairos.domain.id.UserId;
import java.time.Instant;
import java.util.Objects;

/**
 * The sort key of the member list: newest first by {@code users.created_at}, then by user id, on the index
 * {@code users_team_created_ix (team_id, created_at, user_id)}. The pair is unique, so a page continues exactly after
 * its last row.
 *
 * @param createdAt the row's {@code created_at}
 * @param user the row's user id
 */
public record MemberKey(Instant createdAt, UserId user) {

    /** Validates the components. */
    public MemberKey {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(user, "user");
    }
}
