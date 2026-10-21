package dev.kairos.api.accounts.web;

import dev.kairos.api.accounts.application.LoginUseCase;
import dev.kairos.api.accounts.application.LogoutUseCase;
import dev.kairos.api.accounts.application.RefreshUseCase;
import dev.kairos.api.accounts.application.SignupUseCase;
import dev.kairos.api.accounts.web.AccountsDtos.LoginRequest;
import dev.kairos.api.accounts.web.AccountsDtos.RefreshTokenRequest;
import dev.kairos.api.accounts.web.AccountsDtos.SignupRequest;
import dev.kairos.api.accounts.web.AccountsDtos.SignupResponse;
import dev.kairos.api.accounts.web.AccountsDtos.TokenResponse;
import dev.kairos.api.support.problem.Problem;
import dev.kairos.api.support.web.ClientAddresses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signup, login, token refresh and logout (LLD section 3.3, APIs 1 to 4). Signup, login and refresh are public; logout
 * needs a credential like every other operation. The controller only maps HTTP to the use cases and back.
 */
@RestController
@RequestMapping(path = "/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Accounts", description = "Sign up, log in, refresh and revoke tokens, and manage the caller's profile.")
public class AuthController {

    private static final int MAX_USER_AGENT = 256;

    private final SignupUseCase signup;
    private final LoginUseCase login;
    private final RefreshUseCase refresh;
    private final LogoutUseCase logout;
    private final ClientAddresses clients;

    /**
     * Creates the controller.
     *
     * @param signup API 1
     * @param login API 2
     * @param refresh API 3
     * @param logout API 4
     * @param clients finds client addresses for rate limits and sessions
     */
    public AuthController(
            SignupUseCase signup,
            LoginUseCase login,
            RefreshUseCase refresh,
            LogoutUseCase logout,
            ClientAddresses clients) {
        this.signup = Objects.requireNonNull(signup, "signup");
        this.login = Objects.requireNonNull(login, "login");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.logout = Objects.requireNonNull(logout, "logout");
        this.clients = Objects.requireNonNull(clients, "clients");
    }

    /**
     * API 1: signs up.
     *
     * @param body the email and password
     * @param request the HTTP request, for the client address
     * @return 201 with the new user
     */
    @PostMapping(path = "/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Sign up",
            description = "Creates an account with an email and a password (12 to 128 characters, not a common"
                    + " password). The new user has no team until one is created or a member adds them. No email is"
                    + " sent. Rate-limited per client address.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = SignupRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "signup",
                                                            value = AccountsExamples.SIGNUP_REQUEST))))
    @ApiResponse(
            responseCode = "201",
            description = "Created",
            headers = @Header(name = "Location", description = "/v1/me", schema = @Schema(type = "string")),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SignupResponse.class),
                            examples = @ExampleObject(name = "created", value = AccountsExamples.SIGNUP_RESPONSE)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(name = "validation", value = AccountsExamples.SIGNUP_VALIDATION)))
    @ApiResponse(
            responseCode = "409",
            description = "Email already registered",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(name = "email-taken", value = AccountsExamples.EMAIL_TAKEN)))
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
                                            value = AccountsExamples.SIGNUP_RATE_LIMITED)))
    public ResponseEntity<SignupResponse> signUp(@Valid @RequestBody SignupRequest body, HttpServletRequest request) {
        SignupResponse created =
                SignupResponse.of(signup.signUp(body.email(), body.password(), clients.clientAddress(request)));
        return ResponseEntity.created(URI.create("/v1/me")).body(created);
    }

    /**
     * API 2: logs in.
     *
     * @param body the email and password
     * @param request the HTTP request, for the client address and user agent
     * @return 200 with the tokens
     */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Log in",
            description = "Exchanges an email and password for an access token (15 minutes) and a refresh token (30"
                    + " days). Every failure is the same 401; repeated failures lock the account for a growing period,"
                    + " and a locked account gets the same 401. Rate-limited per client address and per account.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = LoginRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "login",
                                                            value = AccountsExamples.LOGIN_REQUEST))))
    @ApiResponse(
            responseCode = "200",
            description = "Logged in",
            headers = @Header(name = "Cache-Control", description = "no-store", schema = @Schema(type = "string")),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TokenResponse.class),
                            examples = @ExampleObject(name = "tokens", value = AccountsExamples.LOGIN_RESPONSE)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(name = "validation", value = AccountsExamples.LOGIN_VALIDATION)))
    @ApiResponse(
            responseCode = "401",
            description = "Login failed",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples =
                                    @ExampleObject(
                                            name = "invalid-credentials",
                                            value = AccountsExamples.INVALID_CREDENTIALS)))
    @ApiResponse(
            responseCode = "429",
            description = "Too many requests",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples =
                                    @ExampleObject(name = "rate-limited", value = AccountsExamples.LOGIN_RATE_LIMITED)))
    public TokenResponse logIn(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return TokenResponse.of(
                login.logIn(body.email(), body.password(), clients.clientAddress(request), userAgent(request)));
    }

    /**
     * API 3: refreshes the tokens.
     *
     * @param body the refresh token
     * @return 200 with the new tokens
     */
    @PostMapping(path = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Refresh tokens",
            description = "Exchanges a refresh token for a new access token and a new refresh token. The presented"
                    + " token is marked used; presenting an already used token revokes the whole session family. The"
                    + " new access token carries the user's current team.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = RefreshTokenRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "refresh",
                                                            value = AccountsExamples.REFRESH_REQUEST))))
    @ApiResponse(
            responseCode = "200",
            description = "Refreshed",
            headers = @Header(name = "Cache-Control", description = "no-store", schema = @Schema(type = "string")),
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TokenResponse.class),
                            examples = @ExampleObject(name = "tokens", value = AccountsExamples.REFRESH_RESPONSE)))
    @ApiResponse(
            responseCode = "401",
            description = "Refresh failed",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples =
                                    @ExampleObject(
                                            name = "invalid-refresh-token",
                                            value = AccountsExamples.INVALID_REFRESH_TOKEN)))
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest body) {
        return TokenResponse.of(refresh.refresh(body.refreshToken()));
    }

    /**
     * API 4: logs out.
     *
     * @param body the refresh token of the session to end
     * @return 204
     */
    @PostMapping(path = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Log out",
            description = "Revokes the session of a refresh token. Unknown or already revoked tokens also return 204."
                    + " Access tokens of the session stop working within 30 seconds.",
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            content =
                                    @Content(
                                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                                            schema = @Schema(implementation = RefreshTokenRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "logout",
                                                            value = AccountsExamples.LOGOUT_REQUEST))))
    @ApiResponse(responseCode = "204", description = "Logged out")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request",
            content =
                    @Content(
                            mediaType = Problem.MEDIA_TYPE,
                            schema = @Schema(implementation = Problem.class),
                            examples = @ExampleObject(name = "validation", value = AccountsExamples.LOGOUT_VALIDATION)))
    public ResponseEntity<Void> logOut(@Valid @RequestBody RefreshTokenRequest body) {
        logout.logOut(body.refreshToken());
        return ResponseEntity.noContent().build();
    }

    // Stored with the session for the user's own reference only; control characters and non-ASCII are dropped.
    private static @Nullable String userAgent(HttpServletRequest request) {
        String value = request.getHeader("User-Agent");
        if (value == null || value.isEmpty()) {
            return null;
        }
        StringBuilder clean = new StringBuilder(Math.min(value.length(), MAX_USER_AGENT));
        for (int i = 0; i < value.length() && clean.length() < MAX_USER_AGENT; i++) {
            char c = value.charAt(i);
            if (c >= ' ' && c < 0x7F) {
                clean.append(c);
            }
        }
        return clean.isEmpty() ? null : clean.toString();
    }
}
