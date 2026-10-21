package dev.kairos.api.accounts;

import dev.kairos.api.accounts.application.LockoutPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the accounts APIs, bound from {@code kairos.api.accounts.*} and documented with their defaults in
 * {@code application.yml}.
 *
 * @param lockout the progressive login lockout
 */
@Validated
@ConfigurationProperties("kairos.api.accounts")
public record AccountsProperties(
        @NotNull @Valid @DefaultValue Lockout lockout) {

    /**
     * The progressive login lockout (LLD section 3.3, API 2).
     *
     * @param threshold consecutive failed logins that lock the account
     * @param base the first lock period; each further failure doubles it
     * @param max the longest lock period
     */
    public record Lockout(
            @Min(1) @Max(100) @DefaultValue("5") int threshold,
            @NotNull @DefaultValue("1m") Duration base,
            @NotNull @DefaultValue("1h") Duration max) {

        /**
         * Returns the policy.
         *
         * @return the lockout policy
         */
        public LockoutPolicy toPolicy() {
            return new LockoutPolicy(threshold, base, max);
        }
    }
}
