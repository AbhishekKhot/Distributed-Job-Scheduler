package dev.kairos.api.team.application;

import dev.kairos.domain.id.UserId;
import java.time.Instant;
import java.util.Objects;

/**
 * A team member (LLD section 3.4, APIs 10 and 11).
 *
 * @param user the user id
 * @param email the normalised email
 * @param addedAt the member's {@code addedAt}; {@code users} has no join time yet, so this is the account's
 *     {@code created_at}, which is also the list order (see {@link MemberKey})
 */
public record Member(UserId user, String email, Instant addedAt) {

    /** Validates the components. */
    public Member {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(addedAt, "addedAt");
    }

    /**
     * Returns the member's position in the list.
     *
     * @return the sort key
     */
    public MemberKey key() {
        return new MemberKey(addedAt, user);
    }

    /** Leaves the email out. */
    @Override
    public String toString() {
        return "Member[user=" + user + ", addedAt=" + addedAt + "]";
    }
}
