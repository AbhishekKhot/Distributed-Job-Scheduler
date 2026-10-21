package dev.kairos.api.accounts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.api.support.problem.ApiException;
import dev.kairos.api.support.ratelimit.RateLimit;
import dev.kairos.domain.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Email normalisation, the lockout rule and the accounts rate-limit messages. */
class AccountRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    @Test
    void emailsAreLowerCased() {
        assertThat(Emails.normalize("Asha.K+ops@Example.COM")).contains("asha.k+ops@example.com");
        assertThat(Emails.normalize("a@b.co")).contains("a@b.co");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "plain",
                "@example.com",
                "a@",
                "a@@example.com",
                "a@b@example.com",
                "a@example",
                "a@-example.com",
                "a@example-.com",
                "a@exa mple.com",
                "a@exa_mple.com",
                "a@example..com",
                ".a@example.com",
                "a.@example.com",
                "a..b@example.com",
                "a b@example.com",
                "a\"b@example.com",
                "é@example.com",
                "a@éxample.com"
            })
    void implausibleEmailsAreRejected(String email) {
        assertThat(Emails.normalize(email)).isEmpty();
    }

    @Test
    void overlongEmailsAreRejected() {
        assertThat(Emails.normalize("a".repeat(65) + "@example.com")).isEmpty();
        assertThat(Emails.normalize("a@" + "b".repeat(64) + ".com")).isEmpty();
        assertThat(Emails.normalize("a@" + "b".repeat(250) + ".com")).isEmpty();
        assertThat(Emails.normalize("a".repeat(64) + "@" + "b".repeat(60) + ".com"))
                .isPresent();
    }

    @Test
    void theLockoutStartsAtTheThresholdAndDoublesUpToTheMaximum() {
        LockoutPolicy policy = new LockoutPolicy(5, Duration.ofMinutes(1), Duration.ofHours(1));

        assertThat(policy.lockedUntil(4, NOW)).isEmpty();
        assertThat(policy.lockedUntil(5, NOW)).contains(NOW.plus(Duration.ofMinutes(1)));
        assertThat(policy.lockedUntil(6, NOW)).contains(NOW.plus(Duration.ofMinutes(2)));
        assertThat(policy.lockedUntil(8, NOW)).contains(NOW.plus(Duration.ofMinutes(8)));
        assertThat(policy.lockedUntil(11, NOW)).contains(NOW.plus(Duration.ofHours(1)));
        assertThat(policy.lockedUntil(32_767, NOW)).contains(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void theLockoutPolicyRejectsNonsense() {
        assertThatThrownBy(() -> new LockoutPolicy(0, Duration.ofMinutes(1), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockoutPolicy(5, Duration.ZERO, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LockoutPolicy(5, Duration.ofHours(2), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRateLimitGetsTheOperationsMessageAndKeepsRetryAfter() {
        AccountRateLimits limits = new AccountRateLimits((limit, subject) -> {
            throw ApiException.rateLimited(Duration.ofSeconds(7));
        });

        assertThatThrownBy(() -> limits.consume(RateLimit.SIGNUP_PER_IP, "10.0.0.1", AccountRateLimits.SIGNUP_DETAIL))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(e.detail()).isEqualTo("Too many signups from this address. Retry later.");
                    assertThat(e.retryAfter()).contains(Duration.ofSeconds(7));
                });
    }

    @Test
    void anUnavailableLimiterStaysUnavailable() {
        ApiException unavailable = ApiException.unavailable(Duration.ofSeconds(5));
        AccountRateLimits limits = new AccountRateLimits((limit, subject) -> {
            throw unavailable;
        });

        assertThatThrownBy(() -> limits.consume(RateLimit.LOGIN_PER_IP, "10.0.0.1", AccountRateLimits.LOGIN_DETAIL))
                .isSameAs(unavailable);
    }

    @Test
    void storedUsersKnowWhetherTheyAreLockedAndHideSecrets() {
        UserAccounts.StoredUser user = new UserAccounts.StoredUser(
                dev.kairos.testsupport.TestIds.userId(),
                null,
                "secret-email@example.com",
                "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA",
                true,
                5,
                NOW.plusSeconds(60));

        assertThat(user.lockedAt(NOW)).isTrue();
        assertThat(user.lockedAt(NOW.plusSeconds(60))).isFalse();
        assertThat(user.toString()).doesNotContain("secret-email").doesNotContain("argon2id");
    }
}
