package dev.kairos.api.accounts.web;

import dev.kairos.api.accounts.application.IssuedTokens;
import dev.kairos.api.accounts.application.UserProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Request and response bodies of the accounts APIs (LLD section 3.3). */
public final class AccountsDtos {

    /** The longest password accepted at login or as the current password; the policy caps new ones at 128. */
    static final int MAX_PRESENTED_PASSWORD = 1024;

    /** The longest refresh token accepted; real ones are 46 characters. */
    static final int MAX_REFRESH_TOKEN = 256;

    private static final String REDACTED = "<redacted>";

    private AccountsDtos() {}

    /**
     * Body of {@code POST /v1/auth/signup}. The password is checked by the password policy, which reports
     * {@code too-short}, {@code too-long}, {@code same-as-email} or {@code common-password}.
     *
     * @param email the email, normalised to lower case
     * @param password the password, 12 to 128 characters
     */
    @Schema(name = "SignupRequest")
    public record SignupRequest(
            @Schema(example = "asha@example.com", maxLength = 254) @NotBlank @Size(max = 254) String email,

            @Schema(example = "<password, 12-128 characters>", minLength = 12, maxLength = 128) @NotNull String password) {

        /** Leaves the password out. */
        @Override
        public String toString() {
            return "SignupRequest[email=" + REDACTED + ", password=" + REDACTED + "]";
        }
    }

    /**
     * Body of {@code POST /v1/auth/login}.
     *
     * @param email the email
     * @param password the password
     */
    @Schema(name = "LoginRequest")
    public record LoginRequest(
            @Schema(example = "asha@example.com", maxLength = 254) @NotBlank @Size(max = 254) String email,

            @Schema(example = "<password, 12-128 characters>") @NotEmpty @Size(max = MAX_PRESENTED_PASSWORD) String password) {

        /** Leaves the email and password out. */
        @Override
        public String toString() {
            return "LoginRequest[email=" + REDACTED + ", password=" + REDACTED + "]";
        }
    }

    /**
     * Body of {@code POST /v1/auth/refresh} and {@code POST /v1/auth/logout}.
     *
     * @param refreshToken the refresh token
     */
    @Schema(name = "RefreshTokenRequest")
    public record RefreshTokenRequest(
            @Schema(example = "rt_q3V9mZ2xK7pL4sN8wB1cD6fH0jR5tY2u") @NotBlank @Size(max = MAX_REFRESH_TOKEN) String refreshToken) {

        /** Leaves the token out. */
        @Override
        public String toString() {
            return "RefreshTokenRequest[refreshToken=" + REDACTED + "]";
        }
    }

    /**
     * Body of {@code POST /v1/me/password}.
     *
     * @param currentPassword the current password
     * @param newPassword the new password, 12 to 128 characters
     */
    @Schema(name = "ChangePasswordRequest")
    public record ChangePasswordRequest(
            @Schema(example = "<password, 12-128 characters>") @NotEmpty @Size(max = MAX_PRESENTED_PASSWORD) String currentPassword,

            @Schema(example = "<new password, 12-128 characters>", minLength = 12, maxLength = 128) @NotNull String newPassword) {

        /** Leaves the passwords out. */
        @Override
        public String toString() {
            return "ChangePasswordRequest[currentPassword=" + REDACTED + ", newPassword=" + REDACTED + "]";
        }
    }

    /**
     * Response of {@code POST /v1/auth/signup}.
     *
     * @param userId the new user's id
     * @param email the normalised email
     * @param teamId always {@code null}: a new user has no team
     * @param createdAt when the account was created
     */
    @Schema(name = "SignupResponse")
    public record SignupResponse(
            @Schema(example = "0192e7a9-1f3c-7d8e-9a0b-1c2d3e4f5a6b")
            UUID userId,

            @Schema(example = "asha@example.com") String email,
            @Schema(nullable = true) @Nullable UUID teamId,
            @Schema(example = "2026-10-14T09:30:00Z") Instant createdAt) {

        static SignupResponse of(UserProfile profile) {
            return new SignupResponse(
                    profile.id().value(),
                    profile.email(),
                    profile.team() == null ? null : profile.team().value(),
                    profile.createdAt());
        }
    }

    /**
     * Response of {@code GET /v1/me}.
     *
     * @param userId the user's id
     * @param email the email
     * @param teamId the user's team, or {@code null}
     * @param teamName the team's name (slug), or {@code null}
     * @param createdAt when the account was created
     */
    @Schema(name = "ProfileResponse")
    public record ProfileResponse(
            @Schema(example = "0192e7a9-1f3c-7d8e-9a0b-1c2d3e4f5a6b")
            UUID userId,

            @Schema(example = "asha@example.com") String email,

            @Schema(example = "0192e7b0-4d2a-7c11-8f3e-6a9b1c2d3e4f", nullable = true) @Nullable UUID teamId,

            @Schema(example = "payments", nullable = true) @Nullable String teamName,

            @Schema(example = "2026-10-14T09:30:00Z") Instant createdAt) {

        static ProfileResponse of(UserProfile profile) {
            return new ProfileResponse(
                    profile.id().value(),
                    profile.email(),
                    profile.team() == null ? null : profile.team().value(),
                    profile.teamName(),
                    profile.createdAt());
        }
    }

    /**
     * Response of {@code POST /v1/auth/login} and {@code POST /v1/auth/refresh}.
     *
     * @param accessToken the access token (JWT, ES256)
     * @param tokenType always {@code Bearer}
     * @param expiresIn the access token's lifetime in seconds
     * @param refreshToken the refresh token, usable once
     * @param refreshExpiresIn the refresh token's lifetime in seconds
     * @param teamId the team the access token carries, or {@code null}
     */
    @Schema(name = "TokenResponse")
    public record TokenResponse(
            @Schema(example = "eyJhbGciOiJFUzI1NiIsImtpZCI6ImsyMDI2MTAifQ.eyJzdWIiOiIwMTkyZTdhOSJ9.sig")
            String accessToken,

            @Schema(example = "Bearer") String tokenType,
            @Schema(example = "900") long expiresIn,

            @Schema(example = "rt_q3V9mZ2xK7pL4sN8wB1cD6fH0jR5tY2u")
            String refreshToken,

            @Schema(example = "2592000") long refreshExpiresIn,

            @Schema(example = "0192e7b0-4d2a-7c11-8f3e-6a9b1c2d3e4f", nullable = true) @Nullable UUID teamId) {

        static TokenResponse of(IssuedTokens tokens) {
            return new TokenResponse(
                    tokens.accessToken().reveal(),
                    "Bearer",
                    tokens.expiresInSeconds(),
                    tokens.refreshToken().reveal(),
                    tokens.refreshExpiresInSeconds(),
                    tokens.team() == null ? null : tokens.team().value());
        }

        /** Leaves the tokens out. */
        @Override
        public String toString() {
            return "TokenResponse[accessToken=" + REDACTED + ", refreshToken=" + REDACTED + ", teamId=" + teamId + "]";
        }
    }
}
