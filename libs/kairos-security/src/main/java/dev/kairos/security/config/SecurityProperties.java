package dev.kairos.security.config;

import dev.kairos.security.password.Argon2idParameters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Security configuration of a service, bound from {@code kairos.security.*}.
 *
 * <p>Secrets come from the environment: a service maps its deployment variables in its {@code application.yml}, for
 * example {@code kairos.security.api-key.pepper: ${KAIROS_API_KEY_PEPPER}} and
 * {@code kairos.security.kek.local-key: ${KAIROS_LOCAL_KEK:}} (local profile) or
 * {@code kairos.security.kek.uri: ${KAIROS_KEK_URI}} (production). Everything else has a default. No secret appears
 * in any {@code toString()}.
 *
 * @param password Argon2id cost of new password hashes
 * @param accessToken access-token issuer, audience, lifetime and skew
 * @param refreshToken refresh-token lifetime
 * @param apiKey API key peppers
 * @param kek the key encryption key
 * @param keysetCache the cache of opened keysets
 */
@Validated
@ConfigurationProperties("kairos.security")
public record SecurityProperties(
        @NotNull @Valid @DefaultValue Password password,
        @NotNull @Valid @DefaultValue AccessToken accessToken,
        @NotNull @Valid @DefaultValue RefreshToken refreshToken,
        @NotNull @Valid @DefaultValue ApiKey apiKey,
        @NotNull @Valid @DefaultValue Kek kek,
        @NotNull @Valid @DefaultValue KeysetCache keysetCache) {

    /**
     * Argon2id cost of new hashes; existing hashes keep their own and are upgraded at the next login. The defaults
     * are the LLD's; they may only be raised.
     *
     * @param memoryKib memory in KiB
     * @param iterations passes
     * @param parallelism lanes
     */
    public record Password(
            @Min(19_456) @Max(262_144) @DefaultValue("19456")
            int memoryKib,

            @Min(2) @Max(16) @DefaultValue("2") int iterations,
            @Min(1) @Max(16) @DefaultValue("1") int parallelism) {

        /**
         * Returns the hash parameters (16-byte salt, 32-byte hash).
         *
         * @return the parameters
         */
        public Argon2idParameters toParameters() {
            return new Argon2idParameters(memoryKib, iterations, parallelism, 16, 32);
        }
    }

    /**
     * Access-token settings.
     *
     * @param issuer the {@code iss} claim
     * @param audience the {@code aud} claim
     * @param ttl the lifetime
     * @param clockSkew the tolerance on {@code exp} and {@code iat}
     */
    public record AccessToken(
            @NotBlank @Size(max = 256) @DefaultValue("kairos")
            String issuer,

            @NotBlank @Size(max = 256) @DefaultValue("kairos-api")
            String audience,

            @NotNull @DefaultValue("15m") Duration ttl,
            @NotNull @DefaultValue("30s") Duration clockSkew) {}

    /**
     * Refresh-token settings.
     *
     * @param ttl the session lifetime
     */
    public record RefreshToken(@NotNull @DefaultValue("30d") Duration ttl) {}

    /**
     * API key peppers: 64 hex characters each.
     *
     * @param pepper the current pepper; required by services that verify API keys
     * @param pepperVersion the current pepper's version
     * @param previousPepper the previous pepper during a rotation
     * @param previousPepperVersion the previous pepper's version
     */
    public record ApiKey(
            @Nullable @Pattern(regexp = "^[0-9a-fA-F]{64}$") String pepper,
            @Min(1) @Max(32_767) @DefaultValue("1") int pepperVersion,
            @Nullable @Pattern(regexp = "^[0-9a-fA-F]{64}$") String previousPepper,
            @Nullable @Min(1) @Max(32_767) Integer previousPepperVersion) {

        /** Leaves the peppers out. */
        @Override
        public String toString() {
            return "ApiKey[pepper=<redacted>, pepperVersion=" + pepperVersion + ", previousPepperVersion="
                    + previousPepperVersion + "]";
        }
    }

    /**
     * The key encryption key: a Cloud KMS URI outside the {@code local} profile, or the local key under it.
     *
     * @param uri the Cloud KMS key, {@code gcp-kms://projects/.../cryptoKeys/...}
     * @param localKey the local key, 64 hex characters ({@code KAIROS_LOCAL_KEK})
     */
    public record Kek(
            @Nullable @Size(max = 512) String uri,
            @Nullable @Pattern(regexp = "^[0-9a-fA-F]{64}$") String localKey) {

        /** Leaves the local key out. */
        @Override
        public String toString() {
            return "Kek[uri=" + uri + ", localKey=" + (localKey == null ? "unset" : "<redacted>") + "]";
        }
    }

    /**
     * The cache of opened keysets.
     *
     * @param expireAfterAccess how long an unused keyset stays open
     * @param maximumSize the most keysets kept open
     */
    public record KeysetCache(
            @NotNull @DefaultValue("10m") Duration expireAfterAccess,
            @Min(1) @Max(1_000_000) @DefaultValue("10000") long maximumSize) {}
}
