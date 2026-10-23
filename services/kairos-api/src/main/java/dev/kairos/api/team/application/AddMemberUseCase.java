package dev.kairos.api.team.application;

import dev.kairos.api.accounts.application.AccountRateLimits;
import dev.kairos.api.accounts.application.Emails;
import dev.kairos.api.security.UserPrincipal;
import dev.kairos.api.support.ratelimit.RateLimit;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.NotFoundException;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.TeamId;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * API 11, {@code POST /v1/team/members} (LLD section 3.4): a member adds an existing user, found by email, who has no
 * team. No email is sent; the added user gets the team on their next token refresh.
 *
 * <p>Order: the per-user rate limit, the email's structure, then one control-database transaction that sets the
 * user's team only if it is still empty (one conditional update, so two teams cannot both win) and writes
 * {@code MEMBER_ADDED} to the audit log. A user who is already a member of the caller's team is returned unchanged
 * and not audited again. API keys are refused before this runs (403 {@code forbidden-for-api-key}). Thread-safe.
 */
public final class AddMemberUseCase {

    /** LLD message of 404 {@code user-not-found}. */
    public static final String USER_NOT_FOUND_DETAIL = "No account exists for this email.";

    /** LLD message of 409 {@code user-in-another-team}. */
    public static final String USER_IN_ANOTHER_TEAM_DETAIL = "The user already belongs to another team.";

    /** LLD message of 429 on member addition. */
    public static final String RATE_LIMITED_DETAIL = "Too many members added. Retry later.";

    private final TeamStore store;
    private final TeamAudit audit;
    private final TeamTransactions transactions;
    private final AccountRateLimits limits;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param store the team store
     * @param audit the audit log
     * @param transactions control-database transactions
     * @param limits the rate limits
     * @param clock the time source
     */
    public AddMemberUseCase(
            TeamStore store, TeamAudit audit, TeamTransactions transactions, AccountRateLimits limits, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Adds a member.
     *
     * @param team the caller's team
     * @param caller the calling user
     * @param email the submitted email
     * @return the member
     * @throws ValidationFailedException 400 {@code validation} for a malformed email
     * @throws NotFoundException 404 {@code user-not-found}
     * @throws ConflictException 409 {@code user-in-another-team}
     */
    public Member add(TeamId team, UserPrincipal caller, String email) {
        limits.consume(RateLimit.MEMBER_ADD_PER_USER, caller.user().value().toString(), RATE_LIMITED_DETAIL);
        String normalized =
                Emails.normalize(email).orElseThrow(() -> ValidationFailedException.of("email", FieldError.FORMAT));
        Instant now = clock.instant();
        return transactions.inTransaction(() -> switch (store.addByEmail(team, normalized, now)) {
            case TeamStore.AddOutcome.Added added -> {
                audit.record(new TeamAudit.Entry(
                        team,
                        caller.actor(),
                        TeamAudit.MEMBER_ADDED,
                        TeamAudit.USER,
                        added.member().user().value().toString(),
                        Map.of()));
                yield added.member();
            }
            case TeamStore.AddOutcome.AlreadyMember existing -> existing.member();
            case TeamStore.AddOutcome.InAnotherTeam _ ->
                throw new ConflictException(ErrorCode.USER_IN_ANOTHER_TEAM, USER_IN_ANOTHER_TEAM_DETAIL);
            case TeamStore.AddOutcome.UnknownUser _ ->
                throw new NotFoundException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_DETAIL);
        });
    }
}
