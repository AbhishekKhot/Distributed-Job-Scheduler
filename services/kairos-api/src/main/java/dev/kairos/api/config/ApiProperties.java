package dev.kairos.api.config;

import dev.kairos.api.support.ratelimit.RateLimit;
import dev.kairos.api.support.ratelimit.RateLimitPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the API foundation, bound from {@code kairos.api.*} and documented with their defaults in
 * {@code application.yml}.
 *
 * @param auth caches of the session and API key checks
 * @param cursor the pagination cursor key
 * @param idempotency lifetimes and limits of idempotent requests
 * @param rateLimits the policy of every rate limit
 * @param trustedProxies CIDRs of proxies whose {@code X-Forwarded-For} is trusted; empty to ignore the header
 */
@Validated
@ConfigurationProperties("kairos.api")
public record ApiProperties(
        @NotNull @Valid @DefaultValue Auth auth,
        @NotNull @Valid Cursor cursor,
        @NotNull @Valid @DefaultValue Idempotency idempotency,
        @NotNull @Valid RateLimits rateLimits,
        @NotNull @Size(max = 64) @DefaultValue List<@NotBlank @Size(max = 64) String> trustedProxies) {

    /**
     * Caches of the credential checks.
     *
     * @param sessionCacheTtl how long a session state is reused, at most 30s
     * @param apiKeyCacheTtl how long a stored API key is reused, at most 60s
     * @param cacheMaximumSize the most entries per cache
     */
    public record Auth(
            @NotNull @DefaultValue("30s") Duration sessionCacheTtl,
            @NotNull @DefaultValue("60s") Duration apiKeyCacheTtl,
            @Min(1) @Max(10_000_000) @DefaultValue("100000") long cacheMaximumSize) {}

    /**
     * The pagination cursor key.
     *
     * @param hmacKey the HMAC key as 64 to 128 hex characters, from the environment; never logged
     */
    public record Cursor(
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64,128}$") String hmacKey) {

        /** Leaves the key out. */
        @Override
        public String toString() {
            return "Cursor[hmacKey=<redacted>]";
        }
    }

    /**
     * Idempotent requests.
     *
     * @param ttl how long a key is honoured
     * @param staleAfter when an in-progress record counts as abandoned by a crashed request
     * @param waitTimeout how long a concurrent repeat waits for the first request
     * @param pollInterval the pause between checks while waiting
     * @param maxBodyBytes the largest request body buffered for hashing
     */
    public record Idempotency(
            @NotNull @DefaultValue("24h") Duration ttl,
            @NotNull @DefaultValue("60s") Duration staleAfter,
            @NotNull @DefaultValue("2s") Duration waitTimeout,
            @NotNull @DefaultValue("50ms") Duration pollInterval,

            @Min(1024) @Max(67_108_864) @DefaultValue("16777216")
            int maxBodyBytes) {}

    /**
     * The rate-limit policies.
     *
     * @param loginPerIp login attempts per client IP
     * @param loginPerAccount login attempts per account
     * @param signupPerIp signups per client IP
     * @param passwordChangePerUser credential changes per user
     * @param teamCreatePerUser team creations per user
     * @param memberAddPerUser member additions per user
     * @param endpointTestPerTeam endpoint tests per team
     * @param teamApi authenticated requests per team
     */
    public record RateLimits(
            @NotNull @Valid Limit loginPerIp,
            @NotNull @Valid Limit loginPerAccount,
            @NotNull @Valid Limit signupPerIp,
            @NotNull @Valid Limit passwordChangePerUser,
            @NotNull @Valid Limit teamCreatePerUser,
            @NotNull @Valid Limit memberAddPerUser,
            @NotNull @Valid Limit endpointTestPerTeam,
            @NotNull @Valid Limit teamApi) {

        /**
         * Returns the policy of every limit.
         *
         * @return the policies by limit
         */
        public Map<RateLimit, RateLimitPolicy> policies() {
            Map<RateLimit, RateLimitPolicy> policies = new EnumMap<>(RateLimit.class);
            policies.put(RateLimit.LOGIN_PER_IP, loginPerIp.toPolicy());
            policies.put(RateLimit.LOGIN_PER_ACCOUNT, loginPerAccount.toPolicy());
            policies.put(RateLimit.SIGNUP_PER_IP, signupPerIp.toPolicy());
            policies.put(RateLimit.PASSWORD_CHANGE_PER_USER, passwordChangePerUser.toPolicy());
            policies.put(RateLimit.TEAM_CREATE_PER_USER, teamCreatePerUser.toPolicy());
            policies.put(RateLimit.MEMBER_ADD_PER_USER, memberAddPerUser.toPolicy());
            policies.put(RateLimit.ENDPOINT_TEST_PER_TEAM, endpointTestPerTeam.toPolicy());
            policies.put(RateLimit.TEAM_API, teamApi.toPolicy());
            return policies;
        }
    }

    /**
     * One token bucket.
     *
     * @param capacity the requests allowed per period, also the burst size
     * @param period the refill period, 1s to 1d
     */
    public record Limit(
            @Min(1) @Max(1_000_000) long capacity, @NotNull Duration period) {

        RateLimitPolicy toPolicy() {
            return new RateLimitPolicy(capacity, period);
        }
    }
}
