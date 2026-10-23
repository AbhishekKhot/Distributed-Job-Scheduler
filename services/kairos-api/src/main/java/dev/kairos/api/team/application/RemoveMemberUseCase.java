package dev.kairos.api.team.application;

import dev.kairos.api.security.UserPrincipal;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.NotFoundException;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.token.RefreshTokenService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * API 12, {@code DELETE /v1/team/members/{userId}} (LLD section 3.4): a member removes another member, or leaves with
 * their own id.
 *
 * <p>One control-database transaction locks every member row of the team (so two concurrent removals cannot leave the
 * team empty), refuses an id that is not a member (404 {@code not-found}) or the last member (409
 * {@code last-member}), clears the user's team, revokes every refresh session of the user and writes
 * {@code MEMBER_REMOVED} or {@code MEMBER_LEFT} (plus {@code SESSIONS_REVOKED} when sessions were revoked) to the audit
 * log. The access-token check sees the revoked sessions and the changed team within its cache lifetime (at most 30s),
 * so the user's tokens stop working without waiting for them to expire. API keys are refused before this runs (403
 * {@code forbidden-for-api-key}). Thread-safe.
 */
public final class RemoveMemberUseCase {

    /** LLD message of 404 {@code not-found}. */
    public static final String NOT_FOUND_DETAIL = "The member does not exist.";

    /** LLD message of 409 {@code last-member}. */
    public static final String LAST_MEMBER_DETAIL = "A team must keep at least one member.";

    private final TeamStore store;
    private final RefreshTokenService refreshTokens;
    private final TeamAudit audit;
    private final TeamTransactions transactions;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param store the team store
     * @param refreshTokens revokes the member's sessions
     * @param audit the audit log
     * @param transactions control-database transactions
     * @param clock the time source
     */
    public RemoveMemberUseCase(
            TeamStore store,
            RefreshTokenService refreshTokens,
            TeamAudit audit,
            TeamTransactions transactions,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.refreshTokens = Objects.requireNonNull(refreshTokens, "refreshTokens");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Removes a member, or lets the caller leave.
     *
     * @param team the caller's team
     * @param caller the calling user
     * @param member the member to remove; the caller's own id to leave
     * @throws NotFoundException 404 {@code not-found} if the id is not a member of the team
     * @throws ConflictException 409 {@code last-member}
     */
    public void remove(TeamId team, UserPrincipal caller, UserId member) {
        Instant now = clock.instant();
        transactions.inTransaction(() -> {
            List<UserId> members = store.lockMembers(team);
            if (!members.contains(member)) {
                throw new NotFoundException(NOT_FOUND_DETAIL);
            }
            if (members.size() == 1) {
                throw new ConflictException(ErrorCode.LAST_MEMBER, LAST_MEMBER_DETAIL);
            }
            store.leave(member, team, now);
            int revoked = refreshTokens.revokeOthers(member, null);
            boolean leaving = member.equals(caller.user());
            String resourceId = member.value().toString();
            audit.record(new TeamAudit.Entry(
                    team,
                    caller.actor(),
                    leaving ? TeamAudit.MEMBER_LEFT : TeamAudit.MEMBER_REMOVED,
                    TeamAudit.USER,
                    resourceId,
                    Map.of("sessionsRevoked", revoked)));
            if (revoked > 0) {
                audit.record(new TeamAudit.Entry(
                        team,
                        caller.actor(),
                        TeamAudit.SESSIONS_REVOKED,
                        TeamAudit.USER,
                        resourceId,
                        Map.of("reason", leaving ? "member-left" : "member-removed", "sessions", revoked)));
            }
            return revoked;
        });
    }
}
