package dev.kairos.api.accounts.web;

import dev.kairos.api.accounts.application.ChangePasswordUseCase;
import dev.kairos.api.accounts.application.ProfileUseCase;
import dev.kairos.api.accounts.web.AccountsDtos.ChangePasswordRequest;
import dev.kairos.api.accounts.web.AccountsDtos.ProfileResponse;
import dev.kairos.api.security.KairosPrincipal;
import dev.kairos.api.security.UserPrincipal;
import dev.kairos.api.support.problem.Problem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's profile and password (LLD section 3.3, APIs 5 and 6). Both need a user access token: an API key has
 * no user, so it gets 401 {@code unauthenticated} with the LLD's "A valid access token is required." The profile works
 * for a user without a team.
 */
@RestController
@RequestMapping(path = "/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Accounts", description = "Sign up, log in, refresh and revoke tokens, and manage the caller's profile.")
public class MeController {

    private final ProfileUseCase profiles;
    private final ChangePasswordUseCase passwords;

    /**
     * Creates the controller.
     *
     * @param profiles API 5
     * @param passwords API 6
     */
    public MeController(ProfileUseCase profiles, ChangePasswordUseCase passwords) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.passwords = Objects.requireNonNull(passwords, "passwords");
    }

    /**
     * API 5: the caller's profile.
     *
     * @param principal the caller
     * @return 200 with the profile
     */
    @GetMapping
    @Operation(
            summary = "Get my profile",
            description = "Returns the calling user's profile, including the team, which is null for a user who has"
                    + " none yet.")
    @ApiResponse(
            responseCode = "200",
            description = "The profile",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ProfileResponse.class),
                            examples = @ExampleObject(name = "profile", value = AccountsExamples.PROFILE_RESPONSE)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication required",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples =
                                    @ExampleObject(
                                            name = "unauthenticated",
                                            value = AccountsExamples.ACCESS_TOKEN_REQUIRED)))
    public ProfileResponse profile(KairosPrincipal principal) {
        return ProfileResponse.of(profiles.profile(user(principal).user()));
    }

    /**
     * API 6: changes the caller's password.
     *
     * @param principal the caller
     * @param body the current and new passwords
     * @return 204
     */
    @PostMapping(path = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Change password",
            description = "Verifies the current password and sets a new one (12 to 128 characters, not a common"
                    + " password). All other sessions of the user are revoked; the caller's own session stays"
                    + " logged in. Rate-limited per user.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = ChangePasswordRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "change",
                                                            value = AccountsExamples.CHANGE_PASSWORD_REQUEST))))
    @ApiResponse(responseCode = "204", description = "Password changed")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request or wrong current password",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = {
                                @ExampleObject(
                                        name = "validation",
                                        value = AccountsExamples.CHANGE_PASSWORD_VALIDATION),
                                @ExampleObject(
                                        name = "invalid-current-password",
                                        value = AccountsExamples.INVALID_CURRENT_PASSWORD)
                            }))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication required",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples =
                                    @ExampleObject(
                                            name = "unauthenticated",
                                            value = AccountsExamples.ACCESS_TOKEN_REQUIRED)))
    @ApiResponse(
            responseCode = "429",
            description = "Too many requests",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples =
                                    @ExampleObject(
                                            name = "rate-limited",
                                            value = AccountsExamples.CHANGE_PASSWORD_RATE_LIMITED)))
    public ResponseEntity<Void> changePassword(
            KairosPrincipal principal, @Valid @RequestBody ChangePasswordRequest body) {
        UserPrincipal user = user(principal);
        passwords.changePassword(user.user(), user.sessionId(), body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    private static UserPrincipal user(KairosPrincipal principal) {
        if (principal instanceof UserPrincipal user) {
            return user;
        }
        throw ProfileUseCase.accessTokenRequired();
    }
}
