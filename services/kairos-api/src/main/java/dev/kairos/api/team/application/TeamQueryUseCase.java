package dev.kairos.api.team.application;

import dev.kairos.api.support.problem.ApiException;
import dev.kairos.domain.id.TeamId;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * APIs 9 and 10, {@code GET /v1/team} and {@code GET /v1/team/members} (LLD section 3.4): the caller's team with its
 * quotas and usage, and its members newest first. The team comes from the credential, so another team is never
 * readable. Thread-safe.
 */
public final class TeamQueryUseCase {

    private final TeamStore store;
    private final TeamUsageReader usage;
    private final TeamQuotas defaultQuotas;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param store the team store
     * @param usage reads usage from the cells
     * @param defaultQuotas the quotas used for any quota the team's row does not set
     * @param clock the time source
     */
    public TeamQueryUseCase(TeamStore store, TeamUsageReader usage, TeamQuotas defaultQuotas, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.usage = Objects.requireNonNull(usage, "usage");
        this.defaultQuotas = Objects.requireNonNull(defaultQuotas, "defaultQuotas");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Reads the caller's team.
     *
     * @param team the caller's team
     * @return the team with its usage
     * @throws ApiException 403 {@code no-team} if the team no longer exists
     */
    public TeamDetails team(TeamId team) {
        TeamStore.StoredTeam stored = store.findTeam(team).orElseThrow(ApiException::noTeam);
        Team found = new Team(
                stored.id(),
                stored.slug(),
                TeamQuotas.withDefaults(stored.quotas(), defaultQuotas),
                stored.createdAt());
        return new TeamDetails(found, usage.usage(team, clock.instant()));
    }

    /**
     * Reads a page of the caller's team's members.
     *
     * @param team the caller's team
     * @param after the key of the previous page's last member, or {@code null} for the first page
     * @param limit the page size, 1 to 200
     * @return the page
     */
    public MemberPage members(TeamId team, @Nullable MemberKey after, int limit) {
        List<Member> rows = store.members(team, after, limit + 1);
        if (rows.size() <= limit) {
            return new MemberPage(rows, null);
        }
        List<Member> page = rows.subList(0, limit);
        return new MemberPage(page, page.get(limit - 1).key());
    }
}
