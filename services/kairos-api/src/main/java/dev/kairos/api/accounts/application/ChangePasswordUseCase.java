package dev.kairos.api.accounts.application;

import dev.kairos.api.support.ratelimit.RateLimit;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.password.PasswordHasher;
import dev.kairos.security.password.PasswordPolicy;
import dev.kairos.security.token.RefreshTokenService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * API 6, {@code POST /v1/me/password} (LLD section 3.3): changes the caller's password and logs out every other
 * session.
 *
 * <p>Order: the per-user rate limit, the policy on the new password, the check of the current password (400
 * {@code invalid-current-password}), then one control-database transaction that stores the new hash, revokes every
 * session of the user except the caller's own family, and writes {@code PASSWORD_CHANGED} (plus
 * {@code SESSIONS_REVOKED} when other sessions existed) to the audit log. Thread-safe.
 */
public final class ChangePasswordUseCase {

    /** LLD message of 400 {@code invalid-current-password}. */
    public static final String INVALID_CURRENT_PASSWORD_DETAIL = "The current password is incorrect.";

    private final UserAccounts users;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final RefreshTokenService refreshTokens;
    private final AccountRateLimits limits;
    private final AccountAudit audit;
    private final AccountTransactions transactions;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param users the user store
     * @param hasher hashes and verifies passwords
     * @param policy the password rules
     * @param refreshTokens revokes the other sessions
     * @param limits the rate limits
     * @param audit the audit log
     * @param transactions control-database transactions
     * @param clock the time source
     */
    public ChangePasswordUseCase(
            UserAccounts users,
            PasswordHasher hasher,
            PasswordPolicy policy,
            RefreshTokenService refreshTokens,
            AccountRateLimits limits,
            AccountAudit audit,
            AccountTransactions transactions,
            Clock clock) {
        this.users = Objects.requireNonNull(users, "users");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.refreshTokens = Objects.requireNonNull(refreshTokens, "refreshTokens");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Changes a password.
     *
     * @param user the calling user
     * @param sessionId the caller's login session (the token's {@code sid}), which stays logged in
     * @param currentPassword the submitted current password
     * @param newPassword the submitted new password
     * @throws ValidationFailedException 400 {@code validation} for a weak new password, or 400
     *     {@code invalid-current-password}
     */
    public void changePassword(UserId user, UUID sessionId, String currentPassword, String newPassword) {
        limits.consume(
                RateLimit.PASSWORD_CHANGE_PER_USER, user.value().toString(), AccountRateLimits.PASSWORD_CHANGE_DETAIL);
        UserAccounts.StoredUser stored = users.findById(user).orElseThrow(ProfileUseCase::accessTokenRequired);
        policy.check("newPassword", newPassword, stored.email());
        if (!hasher.verify(currentPassword, stored.passwordHash())) {
            throw new ValidationFailedException(
                    ErrorCode.INVALID_CURRENT_PASSWORD, INVALID_CURRENT_PASSWORD_DETAIL, List.of());
        }
        String hash = hasher.hash(newPassword);
        Instant now = clock.instant();
        transactions.inTransaction(() -> {
            users.updatePassword(user, hash, now);
            int revoked = refreshTokens.revokeOthers(user, sessionId);
            String actor = AccountAudit.Entry.userActor(user);
            audit.record(new AccountAudit.Entry(
                    stored.team(),
                    actor,
                    AccountAudit.PASSWORD_CHANGED,
                    user,
                    Map.of("otherSessionsRevoked", revoked)));
            if (revoked > 0) {
                audit.record(new AccountAudit.Entry(
                        stored.team(),
                        actor,
                        AccountAudit.SESSIONS_REVOKED,
                        user,
                        Map.of("reason", "password-change", "sessions", revoked)));
            }
            return revoked;
        });
    }
}
