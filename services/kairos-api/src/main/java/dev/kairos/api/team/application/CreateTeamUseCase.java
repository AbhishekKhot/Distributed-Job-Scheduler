package dev.kairos.api.team.application;

import dev.kairos.api.accounts.application.AccountRateLimits;
import dev.kairos.api.security.KairosPrincipal;
import dev.kairos.api.security.UserPrincipal;
import dev.kairos.api.support.problem.ApiException;
import dev.kairos.api.support.ratelimit.RateLimit;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.keyset.KeyPurpose;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.NewSigningKey;
import dev.kairos.security.keyset.SealedKeyset;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

/**
 * API 8, {@code POST /v1/teams} (LLD section 3.4): a user without a team creates one and becomes its first member.
 *
 * <p>Order: an API key is refused with 409 {@code already-in-team} (a key always belongs to a team); then the per-user
 * rate limit, the name rule and a read of the user's team (409 {@code already-in-team}). The team's {@code SECRETS},
 * {@code OUTPUTS} and {@code SIGNING} keysets are created and sealed before the transaction, so no key encryption key
 * call holds a database lock. One control-database transaction then locks the user row, checks again that the user
 * has no team, inserts the team with the default quotas (409 {@code team-name-taken} if the slug exists), makes the
 * user a member, stores the three keysets and writes {@code TEAM_CREATED} to the audit log. The signing key is
 * returned once and never stored in clear. The caller's access token still carries no team; refresh issues one that
 * does. Thread-safe.
 */
public final class CreateTeamUseCase {

    /** LLD message of 409 {@code already-in-team}. */
    public static final String ALREADY_IN_TEAM_DETAIL = "The user already belongs to a team.";

    /** LLD message of 409 {@code team-name-taken}. */
    public static final String TEAM_NAME_TAKEN_DETAIL = "A team with this name already exists.";

    /** LLD message of 429 on team creation. */
    public static final String RATE_LIMITED_DETAIL = "Too many teams created. Retry later.";

    private final TeamStore store;
    private final KeysetService keysets;
    private final TeamAudit audit;
    private final TeamTransactions transactions;
    private final AccountRateLimits limits;
    private final KairosIdFactory ids;
    private final TeamQuotas defaultQuotas;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param store the team store
     * @param keysets creates and seals the team's keysets
     * @param audit the audit log
     * @param transactions control-database transactions
     * @param limits the rate limits
     * @param ids creates team ids
     * @param defaultQuotas the quotas of a new team
     * @param clock the time source
     */
    public CreateTeamUseCase(
            TeamStore store,
            KeysetService keysets,
            TeamAudit audit,
            TeamTransactions transactions,
            AccountRateLimits limits,
            KairosIdFactory ids,
            TeamQuotas defaultQuotas,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.keysets = Objects.requireNonNull(keysets, "keysets");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.defaultQuotas = Objects.requireNonNull(defaultQuotas, "defaultQuotas");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates a team.
     *
     * @param caller the caller
     * @param name the submitted team name
     * @return the team and its signing key
     * @throws ConflictException 409 {@code already-in-team} or {@code team-name-taken}
     * @throws dev.kairos.domain.error.ValidationFailedException 400 {@code validation} for a bad name
     * @throws ApiException 429 {@code rate-limited}, or 401 if the user no longer exists
     */
    public CreatedTeam create(KairosPrincipal caller, String name) {
        if (!(caller instanceof UserPrincipal principal)) {
            throw alreadyInTeam();
        }
        UserId user = principal.user();
        limits.consume(RateLimit.TEAM_CREATE_PER_USER, user.value().toString(), RATE_LIMITED_DETAIL);
        String slug = TeamNames.validate(name);
        if (store.findMembership(user)
                        .orElseThrow(ApiException::unauthenticated)
                        .team()
                != null) {
            throw alreadyInTeam();
        }
        TeamId team = ids.newTeamId();
        SealedKeyset secrets = keysets.createTeamKeyset(team, KeyPurpose.SECRETS);
        SealedKeyset outputs = keysets.createTeamKeyset(team, KeyPurpose.OUTPUTS);
        NewSigningKey signing = keysets.createSigningKey(team);
        // Postgres keeps microseconds; truncating here makes the response equal to what GET /v1/team returns later.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        transactions.inTransaction(() -> {
            if (store.lockUser(user).orElseThrow(ApiException::unauthenticated).team() != null) {
                throw alreadyInTeam();
            }
            if (!store.insertTeam(new TeamStore.NewTeam(team, slug, defaultQuotas, now))) {
                throw new ConflictException(ErrorCode.TEAM_NAME_TAKEN, TEAM_NAME_TAKEN_DETAIL);
            }
            store.join(user, team, now);
            store.insertKeyset(team, KeyPurpose.SECRETS, secrets, now);
            store.insertKeyset(team, KeyPurpose.OUTPUTS, outputs, now);
            store.insertKeyset(team, KeyPurpose.SIGNING, signing.sealed(), now);
            audit.record(new TeamAudit.Entry(
                    team,
                    principal.actor(),
                    TeamAudit.TEAM_CREATED,
                    TeamAudit.TEAM,
                    team.value().toString(),
                    Map.of("name", slug, "signingKeyVersion", signing.version())));
            return team;
        });
        return new CreatedTeam(new Team(team, slug, defaultQuotas, now), signing.exportedKey());
    }

    private static ConflictException alreadyInTeam() {
        return new ConflictException(ErrorCode.ALREADY_IN_TEAM, ALREADY_IN_TEAM_DETAIL);
    }
}
