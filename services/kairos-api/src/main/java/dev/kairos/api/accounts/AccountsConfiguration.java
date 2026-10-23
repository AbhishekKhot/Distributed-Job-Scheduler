package dev.kairos.api.accounts;

import dev.kairos.api.accounts.application.AccountAudit;
import dev.kairos.api.accounts.application.AccountRateLimits;
import dev.kairos.api.accounts.application.AccountTransactions;
import dev.kairos.api.accounts.application.ChangePasswordUseCase;
import dev.kairos.api.accounts.application.LoginUseCase;
import dev.kairos.api.accounts.application.LogoutUseCase;
import dev.kairos.api.accounts.application.ProfileUseCase;
import dev.kairos.api.accounts.application.RefreshUseCase;
import dev.kairos.api.accounts.application.SignupUseCase;
import dev.kairos.api.accounts.application.UserAccounts;
import dev.kairos.api.accounts.infrastructure.ControlAccountTransactions;
import dev.kairos.api.accounts.infrastructure.JdbcAccountAudit;
import dev.kairos.api.accounts.infrastructure.JdbcUserAccounts;
import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.support.ratelimit.RateLimiter;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.config.PasswordConfiguration;
import dev.kairos.security.password.PasswordHasher;
import dev.kairos.security.password.PasswordPolicy;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.security.token.RefreshSessionStore;
import dev.kairos.security.token.RefreshTokenService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wiring of the accounts APIs (IMPLEMENTATION_CHECKLIST T-202).
 *
 * <p>Imports {@link PasswordConfiguration} for Argon2id and the password policy (section 4.3); the token services,
 * the refresh-session store, the rate limiter, the clock and the id factory come from the API foundation. This class
 * adds the SQL adapters of the accounts ports and one bean per use case.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountsProperties.class)
@Import(PasswordConfiguration.class)
public class AccountsConfiguration {

    /** Creates the configuration; Spring calls it. */
    public AccountsConfiguration() {}

    /**
     * The user store on {@code users}.
     *
     * @param control the control database's client
     * @return the store
     */
    @Bean
    public UserAccounts userAccounts(@Qualifier("controlJdbcClient") JdbcClient control) {
        return new JdbcUserAccounts(control);
    }

    /**
     * The accounts audit entries on {@code audit_log}.
     *
     * @param writer the shared audit writer
     * @return the writer
     */
    @Bean
    public AccountAudit accountAudit(AuditWriter writer) {
        return new JdbcAccountAudit(writer);
    }

    /**
     * Control-database transactions of the accounts use cases.
     *
     * @param transactions control-database transactions
     * @return the unit of work
     */
    @Bean
    public AccountTransactions accountTransactions(ControlTransactions transactions) {
        return new ControlAccountTransactions(transactions);
    }

    /**
     * The accounts rate limits with their LLD messages.
     *
     * @param limiter the rate limiter
     * @return the helper
     */
    @Bean
    public AccountRateLimits accountRateLimits(RateLimiter limiter) {
        return new AccountRateLimits(limiter);
    }

    /**
     * API 1, signup.
     *
     * @param users the user store
     * @param hasher hashes passwords
     * @param policy the password rules
     * @param limits the rate limits
     * @param ids the id factory
     * @param clock the clock
     * @return the use case
     */
    @Bean
    public SignupUseCase signupUseCase(
            UserAccounts users,
            PasswordHasher hasher,
            PasswordPolicy policy,
            AccountRateLimits limits,
            KairosIdFactory ids,
            Clock clock) {
        return new SignupUseCase(users, hasher, policy, limits, ids, clock);
    }

    /**
     * API 2, login.
     *
     * @param users the user store
     * @param hasher verifies passwords
     * @param accessTokens issues access tokens
     * @param refreshTokens starts sessions
     * @param limits the rate limits
     * @param audit the audit log
     * @param transactions control-database transactions
     * @param properties the accounts settings
     * @param clock the clock
     * @param meters the meter registry
     * @return the use case
     */
    @Bean
    public LoginUseCase loginUseCase(
            UserAccounts users,
            PasswordHasher hasher,
            AccessTokenService accessTokens,
            RefreshTokenService refreshTokens,
            AccountRateLimits limits,
            AccountAudit audit,
            AccountTransactions transactions,
            AccountsProperties properties,
            Clock clock,
            MeterRegistry meters) {
        return new LoginUseCase(
                users,
                hasher,
                accessTokens,
                refreshTokens,
                limits,
                audit,
                transactions,
                properties.lockout().toPolicy(),
                clock,
                meters);
    }

    /**
     * API 3, refresh.
     *
     * @param refreshTokens rotates refresh tokens
     * @param sessions the refresh-session store
     * @param accessTokens issues access tokens
     * @param users the user store
     * @param audit the audit log
     * @return the use case
     */
    @Bean
    public RefreshUseCase refreshUseCase(
            RefreshTokenService refreshTokens,
            RefreshSessionStore sessions,
            AccessTokenService accessTokens,
            UserAccounts users,
            AccountAudit audit) {
        return new RefreshUseCase(refreshTokens, sessions, accessTokens, users, audit);
    }

    /**
     * API 4, logout.
     *
     * @param refreshTokens revokes sessions
     * @return the use case
     */
    @Bean
    public LogoutUseCase logoutUseCase(RefreshTokenService refreshTokens) {
        return new LogoutUseCase(refreshTokens);
    }

    /**
     * API 5, the profile.
     *
     * @param users the user store
     * @return the use case
     */
    @Bean
    public ProfileUseCase profileUseCase(UserAccounts users) {
        return new ProfileUseCase(users);
    }

    /**
     * API 6, password change.
     *
     * @param users the user store
     * @param hasher hashes and verifies passwords
     * @param policy the password rules
     * @param refreshTokens revokes the other sessions
     * @param limits the rate limits
     * @param audit the audit log
     * @param transactions control-database transactions
     * @param clock the clock
     * @return the use case
     */
    @Bean
    public ChangePasswordUseCase changePasswordUseCase(
            UserAccounts users,
            PasswordHasher hasher,
            PasswordPolicy policy,
            RefreshTokenService refreshTokens,
            AccountRateLimits limits,
            AccountAudit audit,
            AccountTransactions transactions,
            Clock clock) {
        return new ChangePasswordUseCase(users, hasher, policy, refreshTokens, limits, audit, transactions, clock);
    }
}
