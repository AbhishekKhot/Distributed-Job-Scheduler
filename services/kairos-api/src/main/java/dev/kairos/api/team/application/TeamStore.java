package dev.kairos.api.team.application;

import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.keyset.KeyPurpose;
import dev.kairos.security.keyset.SealedKeyset;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Teams and their membership in the control database ({@code teams}, {@code team_keysets} and the {@code team_id} of
 * {@code users}). Writes run in the caller's transaction ({@link TeamTransactions}); methods that lock rows keep the
 * locks until it ends.
 */
public interface TeamStore {

    /**
     * Reads a user's team and locks the user row, so no other request can give the user a team until the transaction
     * ends.
     *
     * @param user the user
     * @return the user's membership, or empty if the user does not exist
     */
    Optional<Membership> lockUser(UserId user);

    /**
     * Reads a user's team without locking.
     *
     * @param user the user
     * @return the user's membership, or empty if the user does not exist
     */
    Optional<Membership> findMembership(UserId user);

    /**
     * Creates a team unless its slug is taken.
     *
     * @param team the new team
     * @return whether the team was created; {@code false} if another team has the slug
     */
    boolean insertTeam(NewTeam team);

    /**
     * Stores one of a team's keysets.
     *
     * @param team the team
     * @param purpose the keyset's purpose
     * @param keyset the sealed keyset
     * @param now the time of the write
     */
    void insertKeyset(TeamId team, KeyPurpose purpose, SealedKeyset keyset, Instant now);

    /**
     * Makes a user a member of a team.
     *
     * @param user the user, locked by {@link #lockUser(UserId)}
     * @param team the team
     * @param now the time of the change
     */
    void join(UserId user, TeamId team, Instant now);

    /**
     * Reads a team.
     *
     * @param team the team
     * @return the stored team, or empty if it does not exist
     */
    Optional<StoredTeam> findTeam(TeamId team);

    /**
     * Reads a page of a team's members, newest first.
     *
     * @param team the team
     * @param after the key of the previous page's last member, or {@code null} for the first page
     * @param limit the most members to return
     * @return the members, in list order
     */
    List<Member> members(TeamId team, @Nullable MemberKey after, int limit);

    /**
     * Adds the user with an email to a team if the user has no team, in one statement.
     *
     * @param team the team
     * @param email the normalised email
     * @param now the time of the change
     * @return what happened
     */
    AddOutcome addByEmail(TeamId team, String email, Instant now);

    /**
     * Locks every member row of a team, so concurrent removals see each other and the team never loses its last
     * member.
     *
     * @param team the team
     * @return the members' ids
     */
    List<UserId> lockMembers(TeamId team);

    /**
     * Clears a member's team.
     *
     * @param user the member, locked by {@link #lockMembers(TeamId)}
     * @param team the team the member leaves
     * @param now the time of the change
     */
    void leave(UserId user, TeamId team, Instant now);

    /**
     * A user's current team.
     *
     * @param team the team, or {@code null} for a user without one
     */
    record Membership(@Nullable TeamId team) {}

    /**
     * A team to create.
     *
     * @param id the new team's id
     * @param slug the validated slug
     * @param quotas the quotas to store
     * @param createdAt the creation time
     */
    record NewTeam(TeamId id, String slug, TeamQuotas quotas, Instant createdAt) {

        /** Validates the components. */
        public NewTeam {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(slug, "slug");
            Objects.requireNonNull(quotas, "quotas");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * A stored team.
     *
     * @param id the team id
     * @param slug the slug
     * @param quotas the stored quotas by JSON key; non-numeric values are left out
     * @param createdAt the creation time
     */
    record StoredTeam(TeamId id, String slug, Map<String, Long> quotas, Instant createdAt) {

        /** Validates the components and copies the quotas. */
        public StoredTeam {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(slug, "slug");
            Objects.requireNonNull(createdAt, "createdAt");
            quotas = Map.copyOf(quotas);
        }
    }

    /** The outcome of {@link #addByEmail(TeamId, String, Instant)}. */
    sealed interface AddOutcome {

        /**
         * The user had no team and is now a member.
         *
         * @param member the new member
         */
        record Added(Member member) implements AddOutcome {}

        /**
         * The user is already a member of this team; nothing changed.
         *
         * @param member the member
         */
        record AlreadyMember(Member member) implements AddOutcome {}

        /** The user belongs to another team. */
        record InAnotherTeam() implements AddOutcome {}

        /** No account has the email. */
        record UnknownUser() implements AddOutcome {}
    }
}
