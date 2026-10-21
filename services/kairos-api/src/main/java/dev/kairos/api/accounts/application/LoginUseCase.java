package dev.kairos.api.accounts.application;

import dev.kairos.api.support.problem.ApiException;
import dev.kairos.api.support.ratelimit.RateLimit;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.security.password.PasswordHasher;
import dev.kairos.security.token.AccessToken;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.security.token.IssuedRefreshToken;
import dev.kairos.security.token.RefreshTokenService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * API 2, {@code POST /v1/auth/login} (LLD section 3.3): checks an email and password and starts a login session.
 *
 * <p>Every refusal is the same 401 {@code invalid-credentials}: unknown email, wrong password, locked or disabled
 * account. Every path spends exactly one Argon2id verification (a dummy one for an unknown email, the real one even
 * for a locked or disabled account), so response times do not reveal which emails are registered. Failures are
 * counted per account; reaching the {@link LockoutPolicy} threshold locks the account for a growing period and writes
 * a {@code LOGIN_LOCKED} audit entry in the same transaction. A success resets the count, upgrades an outdated hash,
 * starts a refresh-token family and issues an access token whose {@code sid} is that family.
 *
 * <p>Both rate limits (per client IP and per account) are taken before any password work. Thread-safe.
 */
public final class LoginUseCase {

    /** LLD message of 401 {@code invalid-credentials}. */
    public static final String INVALID_CREDENTIALS_DETAIL = "Invalid email or password.";

    /** Meter name of failed logins; Prometheus shows it as {@code kairos_login_failures_total}. */
    public static final String FAILURES_COUNTER = "kairos.login.failures";

    private final UserAccounts users;
    private final PasswordHasher hasher;
    private final AccessTokenService accessTokens;
    private final RefreshTokenService refreshTokens;
    private final AccountRateLimits limits;
    private final AccountAudit audit;
    private final AccountTransactions transactions;
    private final LockoutPolicy lockout;
    private final Clock clock;
    private final Counter failures;

    /**
     * Creates the use case.
     *
     * @param users the user store
     * @param hasher verifies passwords
     * @param accessTokens issues access tokens
     * @param refreshTokens starts refresh-token families
     * @param limits the rate limits
     * @param audit the audit log
     * @param transactions control-database transactions
     * @param lockout the lockout rule
     * @param clock the time source
     * @param meters where the failure counter is registered
     */
    public LoginUseCase(
            UserAccounts users,
            PasswordHasher hasher,
            AccessTokenService accessTokens,
            RefreshTokenService refreshTokens,
            AccountRateLimits limits,
            AccountAudit audit,
            AccountTransactions transactions,
            LockoutPolicy lockout,
            Clock clock,
            MeterRegistry meters) {
        this.users = Objects.requireNonNull(users, "users");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.accessTokens = Objects.requireNonNull(accessTokens, "accessTokens");
        this.refreshTokens = Objects.requireNonNull(refreshTokens, "refreshTokens");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.lockout = Objects.requireNonNull(lockout, "lockout");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.failures = Counter.builder(FAILURES_COUNTER)
                .description("Login attempts refused with invalid-credentials")
                .register(meters);
    }

    /**
     * Logs a user in.
     *
     * @param email the submitted email
     * @param password the submitted password
     * @param clientAddress the client's IP address, for the rate limit and the session
     * @param userAgent the client's user agent, or {@code null}
     * @return the access and refresh tokens
     * @throws ApiException 401 {@code invalid-credentials}, or 429 {@code rate-limited}
     */
    public IssuedTokens logIn(String email, String password, String clientAddress, @Nullable String userAgent) {
        limits.consume(RateLimit.LOGIN_PER_IP, clientAddress, AccountRateLimits.LOGIN_DETAIL);
        // The account limit is keyed by what was typed, so malformed emails are limited too.
        String lookup = email.toLowerCase(Locale.ROOT);
        limits.consume(RateLimit.LOGIN_PER_ACCOUNT, lookup, AccountRateLimits.LOGIN_DETAIL);
        Optional<UserAccounts.StoredUser> found = Emails.normalize(email).flatMap(users::findByEmail);
        if (found.isEmpty()) {
            hasher.verifyDummy(password);
            throw invalidCredentials();
        }
        UserAccounts.StoredUser user = found.get();
        Instant now = clock.instant();
        // The real verification runs even when the result is ignored, so a locked or disabled account costs the same.
        boolean matches = hasher.verify(password, user.passwordHash());
        if (!user.active() || user.lockedAt(now)) {
            throw invalidCredentials();
        }
        if (!matches) {
            recordFailure(user, now);
            throw invalidCredentials();
        }
        users.recordLogin(user.id(), now, hasher.needsRehash(user.passwordHash()) ? hasher.hash(password) : null);
        IssuedRefreshToken refresh = refreshTokens.issue(user.id(), userAgent, clientAddress);
        AccessToken access =
                accessTokens.issue(user.id(), user.team(), refresh.session().familyId());
        return IssuedTokens.of(access, refresh, user.team());
    }

    private void recordFailure(UserAccounts.StoredUser user, Instant now) {
        transactions.inTransaction(() -> {
            int count = users.incrementFailedLogins(user.id(), now);
            Optional<Instant> lockedUntil = lockout.lockedUntil(count, now);
            if (lockedUntil.isPresent()) {
                users.lock(user.id(), lockedUntil.get(), now);
                audit.record(new AccountAudit.Entry(
                        user.team(),
                        AccountAudit.LOCKOUT_ACTOR,
                        AccountAudit.LOGIN_LOCKED,
                        user.id(),
                        Map.of(
                                "failedLogins",
                                count,
                                "lockedUntil",
                                lockedUntil.get().toString())));
            }
            return count;
        });
    }

    private ApiException invalidCredentials() {
        failures.increment();
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_DETAIL, null);
    }
}
