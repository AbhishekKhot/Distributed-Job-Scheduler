package dev.kairos.api.team;

import dev.kairos.api.accounts.application.AccountRateLimits;
import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.team.application.AddMemberUseCase;
import dev.kairos.api.team.application.CreateTeamUseCase;
import dev.kairos.api.team.application.RemoveMemberUseCase;
import dev.kairos.api.team.application.TeamAudit;
import dev.kairos.api.team.application.TeamQueryUseCase;
import dev.kairos.api.team.application.TeamStore;
import dev.kairos.api.team.application.TeamTransactions;
import dev.kairos.api.team.application.TeamUsageReader;
import dev.kairos.api.team.infrastructure.ControlTeamTransactions;
import dev.kairos.api.team.infrastructure.JdbcTeamAudit;
import dev.kairos.api.team.infrastructure.JdbcTeamStore;
import dev.kairos.api.team.infrastructure.JdbcTeamUsageReader;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.token.RefreshTokenService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wiring of the team APIs (IMPLEMENTATION_CHECKLIST T-203a).
 *
 * <p>The keyset service (T-109), the refresh-token service, the cell router, the rate limits, the clock and the id
 * factory come from the API foundation and the accounts feature. This class adds the SQL adapters of the team ports
 * and one bean per use case.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TeamProperties.class)
public class TeamConfiguration {

    /** Creates the configuration; Spring calls it. */
    public TeamConfiguration() {}

    /**
     * Teams and membership on the control database.
     *
     * @param control the control database's client
     * @param json the JSON mapper
     * @return the store
     */
    @Bean
    public TeamStore teamStore(@Qualifier("controlJdbcClient") JdbcClient control, JsonMapper json) {
        return new JdbcTeamStore(control, json);
    }

    /**
     * Usage counters from every cell.
     *
     * @param cells the cell router
     * @return the reader
     */
    @Bean
    public TeamUsageReader teamUsageReader(CellRouter cells) {
        return new JdbcTeamUsageReader(cells);
    }

    /**
     * The team audit entries on {@code audit_log}.
     *
     * @param writer the shared audit writer
     * @return the writer
     */
    @Bean
    public TeamAudit teamAudit(AuditWriter writer) {
        return new JdbcTeamAudit(writer);
    }

    /**
     * Control-database transactions of the team use cases.
     *
     * @param transactions control-database transactions
     * @return the unit of work
     */
    @Bean
    public TeamTransactions teamTransactions(ControlTransactions transactions) {
        return new ControlTeamTransactions(transactions);
    }

    /**
     * API 8, team creation.
     *
     * @param store the team store
     * @param keysets creates the team's keysets
     * @param audit the audit log
     * @param transactions control-database transactions
     * @param limits the rate limits
     * @param ids the id factory
     * @param properties the team settings
     * @param clock the clock
     * @return the use case
     */
    @Bean
    public CreateTeamUseCase createTeamUseCase(
            TeamStore store,
            KeysetService keysets,
            TeamAudit audit,
            TeamTransactions transactions,
            AccountRateLimits limits,
            KairosIdFactory ids,
            TeamProperties properties,
            Clock clock) {
        return new CreateTeamUseCase(
                store,
                keysets,
                audit,
                transactions,
                limits,
                ids,
                properties.defaultQuotas().toQuotas(),
                clock);
    }

    /**
     * APIs 9 and 10, the team and its members.
     *
     * @param store the team store
     * @param usage the usage reader
     * @param properties the team settings
     * @param clock the clock
     * @return the use case
     */
    @Bean
    public TeamQueryUseCase teamQueryUseCase(
            TeamStore store, TeamUsageReader usage, TeamProperties properties, Clock clock) {
        return new TeamQueryUseCase(store, usage, properties.defaultQuotas().toQuotas(), clock);
    }

    /**
     * API 11, member addition.
     *
     * @param store the team store
     * @param audit the audit log
     * @param transactions control-database transactions
     * @param limits the rate limits
     * @param clock the clock
     * @return the use case
     */
    @Bean
    public AddMemberUseCase addMemberUseCase(
            TeamStore store, TeamAudit audit, TeamTransactions transactions, AccountRateLimits limits, Clock clock) {
        return new AddMemberUseCase(store, audit, transactions, limits, clock);
    }

    /**
     * API 12, member removal.
     *
     * @param store the team store
     * @param refreshTokens revokes the member's sessions
     * @param audit the audit log
     * @param transactions control-database transactions
     * @param clock the clock
     * @return the use case
     */
    @Bean
    public RemoveMemberUseCase removeMemberUseCase(
            TeamStore store,
            RefreshTokenService refreshTokens,
            TeamAudit audit,
            TeamTransactions transactions,
            Clock clock) {
        return new RemoveMemberUseCase(store, refreshTokens, audit, transactions, clock);
    }
}
