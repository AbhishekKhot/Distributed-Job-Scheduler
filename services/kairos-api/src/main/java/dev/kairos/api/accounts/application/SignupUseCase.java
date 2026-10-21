package dev.kairos.api.accounts.application;

import dev.kairos.api.support.ratelimit.RateLimit;
import dev.kairos.domain.error.ConflictException;
import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.password.PasswordHasher;
import dev.kairos.security.password.PasswordPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * API 1, {@code POST /v1/auth/signup} (LLD section 3.3): creates a user without a team.
 *
 * <p>Order: the per-IP rate limit, the email's structure, the password policy, then the Argon2id hash and an insert
 * that does nothing if the email is taken (409 {@code email-taken}). The hash is computed before the insert in every
 * case, so a taken email answers no faster than a new one. Thread-safe.
 */
public final class SignupUseCase {

    /** LLD message of 409 {@code email-taken}. */
    public static final String EMAIL_TAKEN_DETAIL = "An account with this email already exists.";

    private final UserAccounts users;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final AccountRateLimits limits;
    private final KairosIdFactory ids;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param users the user store
     * @param hasher hashes passwords
     * @param policy the password rules
     * @param limits the rate limits
     * @param ids creates user ids
     * @param clock the time source
     */
    public SignupUseCase(
            UserAccounts users,
            PasswordHasher hasher,
            PasswordPolicy policy,
            AccountRateLimits limits,
            KairosIdFactory ids,
            Clock clock) {
        this.users = Objects.requireNonNull(users, "users");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Signs a user up.
     *
     * @param email the submitted email
     * @param password the submitted password
     * @param clientAddress the client's IP address, for the rate limit
     * @return the new user's profile
     * @throws ValidationFailedException 400 {@code validation} for a malformed email or a weak password
     * @throws ConflictException 409 {@code email-taken}
     */
    public UserProfile signUp(String email, String password, String clientAddress) {
        limits.consume(RateLimit.SIGNUP_PER_IP, clientAddress, AccountRateLimits.SIGNUP_DETAIL);
        String normalized =
                Emails.normalize(email).orElseThrow(() -> ValidationFailedException.of("email", FieldError.FORMAT));
        policy.check("password", password, normalized);
        String hash = hasher.hash(password);
        UserId id = ids.newUserId();
        // Postgres keeps microseconds; truncating here makes the response equal to what GET /v1/me returns later.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!users.insert(new UserAccounts.NewUser(id, normalized, hash, now))) {
            throw new ConflictException(ErrorCode.EMAIL_TAKEN, EMAIL_TAKEN_DETAIL);
        }
        return new UserProfile(id, normalized, null, null, now);
    }
}
