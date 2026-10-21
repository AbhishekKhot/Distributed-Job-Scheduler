package dev.kairos.api.accounts.application;

import dev.kairos.api.support.problem.ApiException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.security.token.AccessToken;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.security.token.RefreshResult;
import dev.kairos.security.token.RefreshSession;
import dev.kairos.security.token.RefreshSessionStore;
import dev.kairos.security.token.RefreshTokenService;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * API 3, {@code POST /v1/auth/refresh} (LLD section 3.3): exchanges a refresh token for a new pair.
 *
 * <p>The presented token is rotated (marked used, a new one added to the same family). Presenting an already used
 * token revokes the whole family, which ends every token of that login, and writes a {@code SESSIONS_REVOKED} audit
 * entry. The new access token carries the user's current team, so a user who created or joined a team picks it up
 * here. A disabled or deleted user gets no tokens, and the new family member is revoked at once. Every refusal is 401
 * {@code invalid-refresh-token}. Thread-safe.
 */
public final class RefreshUseCase {

    /** LLD message of 401 {@code invalid-refresh-token}. */
    public static final String INVALID_REFRESH_TOKEN_DETAIL = "The refresh token is invalid, expired or already used.";

    private static final Logger LOG = LoggerFactory.getLogger(RefreshUseCase.class);

    private final RefreshTokenService refreshTokens;
    private final RefreshSessionStore sessions;
    private final AccessTokenService accessTokens;
    private final UserAccounts users;
    private final AccountAudit audit;

    /**
     * Creates the use case.
     *
     * @param refreshTokens rotates refresh tokens
     * @param sessions the session store, to find the owner of a reused token
     * @param accessTokens issues access tokens
     * @param users the user store
     * @param audit the audit log
     */
    public RefreshUseCase(
            RefreshTokenService refreshTokens,
            RefreshSessionStore sessions,
            AccessTokenService accessTokens,
            UserAccounts users,
            AccountAudit audit) {
        this.refreshTokens = Objects.requireNonNull(refreshTokens, "refreshTokens");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.accessTokens = Objects.requireNonNull(accessTokens, "accessTokens");
        this.users = Objects.requireNonNull(users, "users");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /**
     * Refreshes the tokens.
     *
     * @param presented the refresh token from the request
     * @return the new access and refresh tokens
     * @throws ApiException 401 {@code invalid-refresh-token}
     */
    public IssuedTokens refresh(String presented) {
        RefreshResult result = refreshTokens.rotate(presented);
        if (result instanceof RefreshResult.Rejected rejected) {
            LOG.debug("Refresh token rejected: {}", rejected.reason());
            if (rejected.reason() == RefreshResult.Reason.REUSED) {
                auditReuse(presented);
            }
            throw invalid();
        }
        RefreshResult.Rotated rotated = (RefreshResult.Rotated) result;
        RefreshSession session = rotated.issued().session();
        Optional<UserAccounts.StoredUser> user = users.findById(session.user());
        if (user.isEmpty() || !user.get().active()) {
            refreshTokens.revoke(rotated.issued().token().reveal());
            throw invalid();
        }
        AccessToken access = accessTokens.issue(session.user(), user.get().team(), session.familyId());
        return IssuedTokens.of(access, rotated.issued(), user.get().team());
    }

    private void auditReuse(String presented) {
        Optional<RefreshSession> session = sessions.findByTokenHash(RefreshTokenService.hash(presented));
        if (session.isEmpty()) {
            return;
        }
        RefreshSession reused = session.get();
        audit.record(new AccountAudit.Entry(
                users.findById(reused.user()).map(UserAccounts.StoredUser::team).orElse(null),
                AccountAudit.REUSE_ACTOR,
                AccountAudit.SESSIONS_REVOKED,
                reused.user(),
                Map.of(
                        "reason",
                        "refresh-token-reuse",
                        "familyId",
                        reused.familyId().toString())));
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_REFRESH_TOKEN, INVALID_REFRESH_TOKEN_DETAIL, null);
    }
}
