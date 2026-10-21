package dev.kairos.api.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.security.BearerAuthenticator;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.apikey.ApiKeyService;
import dev.kairos.security.apikey.IssuedApiKey;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The accounts APIs (LLD section 3.3, APIs 1 to 7) over real HTTP against Postgres and Redis: every success and error
 * row, the lockout, refresh rotation and reuse, session revocation and the audit entries.
 *
 * <p>The loopback address is a trusted proxy here, so each test sends its own {@code X-Forwarded-For} client address
 * and the per-IP buckets of different tests never meet in the shared Redis.
 */
@TestPropertySource(properties = "kairos.api.trusted-proxies=127.0.0.1/32,::1/128")
class AccountsIntegrationTest extends ApiApplicationTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private BearerAuthenticator authenticator;

    @Autowired
    private ApiKeyService apiKeys;

    // API 1

    @Test
    void signupCreatesAUserWithoutATeam() {
        String email = "Asha." + ApiTestFixtures.randomHex().substring(0, 12) + "@Example.com";

        HttpResponse<String> response = post("/v1/auth/signup", null, signupBody(email, password()), newIp());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).contains("/v1/me");
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("email").asString()).isEqualTo(email.toLowerCase(java.util.Locale.ROOT));
        assertThat(body.has("teamId")).isTrue();
        assertThat(body.path("teamId").isNull()).isTrue();
        assertThat(body.path("createdAt").asString()).endsWith("Z");
        UUID userId = UUID.fromString(body.path("userId").asString());
        Map<String, Object> row = ApiTestFixtures.control()
                .sql("SELECT email, password_hash, team_id, status FROM scheduler.users WHERE user_id = :id")
                .param("id", userId)
                .query()
                .singleRow();
        assertThat(row.get("email")).isEqualTo(email.toLowerCase(java.util.Locale.ROOT));
        assertThat((String) row.get("password_hash")).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(row.get("team_id")).isNull();
        assertThat(row.get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void signupRejectsAWeakPasswordWithTheLldProblem() {
        String ip = newIp();
        JsonNode tooShort =
                problem(post("/v1/auth/signup", null, signupBody(newEmail(), "short"), ip), 400, "validation");
        assertThat(tooShort.path("detail").asString())
                .isEqualTo("Password must be 12 to 128 characters and not a common password.");
        assertThat(tooShort.path("errors").toString()).isEqualTo("[{\"field\":\"password\",\"code\":\"too-short\"}]");

        String email = newEmail();
        JsonNode sameAsEmail = problem(post("/v1/auth/signup", null, signupBody(email, email), ip), 400, "validation");
        assertThat(sameAsEmail.path("errors").path(0).path("code").asString()).isEqualTo("same-as-email");
    }

    @Test
    void signupRejectsMalformedOrMissingFields() {
        String ip = newIp();
        JsonNode malformed =
                problem(post("/v1/auth/signup", null, signupBody("not-an-email", password()), ip), 400, "validation");
        assertThat(malformed.path("detail").asString()).isEqualTo("One or more fields are invalid.");
        assertThat(malformed.path("errors").toString()).isEqualTo("[{\"field\":\"email\",\"code\":\"format\"}]");

        JsonNode missing =
                problem(post("/v1/auth/signup", null, "{\"email\": \"a@example.com\"}", ip), 400, "validation");
        assertThat(missing.path("errors").toString()).contains("\"field\":\"password\"", "\"code\":\"required\"");

        JsonNode unknown = problem(
                post(
                        "/v1/auth/signup",
                        null,
                        "{\"email\": \"a@example.com\", \"password\": \"x\", \"teamId\": \"t\"}",
                        ip),
                400,
                "validation");
        assertThat(unknown.path("errors").toString()).contains("unknown-field");
    }

    @Test
    void signupWithATakenEmailIs409() {
        String email = newEmail();
        assertThat(post("/v1/auth/signup", null, signupBody(email, password()), newIp())
                        .statusCode())
                .isEqualTo(201);

        JsonNode problem = problem(
                post(
                        "/v1/auth/signup",
                        null,
                        signupBody(email.toUpperCase(java.util.Locale.ROOT), password()),
                        newIp()),
                409,
                "email-taken");
        assertThat(problem.path("title").asString()).isEqualTo("Email already registered");
        assertThat(problem.path("detail").asString()).isEqualTo("An account with this email already exists.");
    }

    @Test
    void signupIsRateLimitedPerClientAddress() {
        String ip = newIp();
        for (int i = 0; i < 10; i++) {
            assertThat(post("/v1/auth/signup", null, signupBody(newEmail(), password()), ip)
                            .statusCode())
                    .isEqualTo(201);
        }

        HttpResponse<String> limited = post("/v1/auth/signup", null, signupBody(newEmail(), password()), ip);

        JsonNode problem = problem(limited, 429, "rate-limited");
        assertThat(problem.path("detail").asString()).isEqualTo("Too many signups from this address. Retry later.");
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(post("/v1/auth/signup", null, signupBody(newEmail(), password()), newIp())
                        .statusCode())
                .isEqualTo(201);
    }

    // API 2

    @Test
    void loginReturnsTokensWhoseSessionIsTheRefreshFamily() {
        Account account = signUp();

        HttpResponse<String> response = post("/v1/auth/login", null, loginBody(account), newIp());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("tokenType").asString()).isEqualTo("Bearer");
        assertThat(body.path("expiresIn").asLong()).isEqualTo(900);
        assertThat(body.path("refreshExpiresIn").asLong()).isEqualTo(2_592_000);
        assertThat(body.path("refreshToken").asString()).startsWith("rt_").hasSize(46);
        assertThat(body.has("teamId")).isTrue();
        assertThat(body.path("teamId").isNull()).isTrue();

        HttpResponse<String> me = send("GET", "/v1/me", body.path("accessToken").asString(), null);
        assertThat(me.statusCode()).as(me.body()).isEqualTo(200);
        Map<String, Object> session = ApiTestFixtures.control()
                .sql(
                        "SELECT status, user_agent, host(ip_address) AS ip FROM scheduler.user_sessions WHERE user_id = :u")
                .param("u", account.id().value())
                .query()
                .singleRow();
        assertThat(session.get("status")).isEqualTo("ACTIVE");
        Map<String, Object> user = ApiTestFixtures.control()
                .sql("SELECT last_login_at, failed_logins FROM scheduler.users WHERE user_id = :u")
                .param("u", account.id().value())
                .query()
                .singleRow();
        assertThat(user.get("last_login_at")).isNotNull();
        assertThat(((Number) user.get("failed_logins")).intValue()).isZero();
    }

    @Test
    void loginCarriesTheTeamOfAMember() {
        Account account = signUp();
        TeamId team = ApiTestFixtures.insertTeam();
        setTeam(account.id(), team);

        JsonNode tokens = logIn(account);

        assertThat(tokens.path("teamId").asString()).isEqualTo(team.value().toString());
        JsonNode me =
                json.readTree(send("GET", "/v1/me", tokens.path("accessToken").asString(), null)
                        .body());
        assertThat(me.path("teamId").asString()).isEqualTo(team.value().toString());
        assertThat(me.path("teamName").asString()).startsWith("t");
    }

    @Test
    void wrongPasswordUnknownEmailAndDisabledAccountsGetTheSameGeneric401() {
        Account account = signUp();
        String ip = newIp();

        JsonNode wrong = problem(
                post("/v1/auth/login", null, loginBody(account.email(), "wrong-password-123"), ip),
                401,
                "invalid-credentials");
        JsonNode unknown = problem(
                post("/v1/auth/login", null, loginBody(newEmail(), account.password()), ip),
                401,
                "invalid-credentials");
        JsonNode malformed = problem(
                post("/v1/auth/login", null, loginBody("nobody", account.password()), ip), 401, "invalid-credentials");
        ApiTestFixtures.control()
                .sql("UPDATE scheduler.users SET status = 'DISABLED' WHERE user_id = :u")
                .param("u", account.id().value())
                .update();
        JsonNode disabled = problem(post("/v1/auth/login", null, loginBody(account), ip), 401, "invalid-credentials");

        for (JsonNode problem : List.of(wrong, unknown, malformed, disabled)) {
            assertThat(problem.path("title").asString()).isEqualTo("Login failed");
            assertThat(problem.path("detail").asString()).isEqualTo("Invalid email or password.");
        }
    }

    @Test
    void loginValidatesTheBody() {
        JsonNode problem = problem(
                post("/v1/auth/login", null, "{\"password\": \"whatever-password\"}", newIp()), 400, "validation");

        assertThat(problem.path("detail").asString()).isEqualTo("One or more fields are invalid.");
        assertThat(problem.path("errors").toString()).isEqualTo("[{\"field\":\"email\",\"code\":\"required\"}]");
    }

    @Test
    void repeatedFailuresLockTheAccountAndAuditTheBurst() {
        Account account = signUp();
        String ip = newIp();
        for (int i = 0; i < 5; i++) {
            problem(
                    post("/v1/auth/login", null, loginBody(account.email(), "wrong-password-123"), ip),
                    401,
                    "invalid-credentials");
        }
        Map<String, Object> locked = userRow(account.id());
        assertThat(((Number) locked.get("failed_logins")).intValue()).isEqualTo(5);
        assertThat(locked.get("locked_until")).isNotNull();

        // The right password does not open a locked account, and the attempt is not counted.
        problem(post("/v1/auth/login", null, loginBody(account), ip), 401, "invalid-credentials");
        assertThat(((Number) userRow(account.id()).get("failed_logins")).intValue())
                .isEqualTo(5);

        List<Map<String, Object>> audit = audit(account.id(), "LOGIN_LOCKED");
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor")).isEqualTo("system:login-lockout");
        assertThat(audit.getFirst().get("details").toString()).contains("\"failedLogins\": 5");

        ApiTestFixtures.control()
                .sql("UPDATE scheduler.users SET locked_until = now() - interval '1 second' WHERE user_id = :u")
                .param("u", account.id().value())
                .update();
        assertThat(post("/v1/auth/login", null, loginBody(account), ip).statusCode())
                .isEqualTo(200);
        Map<String, Object> unlocked = userRow(account.id());
        assertThat(((Number) unlocked.get("failed_logins")).intValue()).isZero();
        assertThat(unlocked.get("locked_until")).isNull();
    }

    @Test
    void loginIsRateLimitedPerClientAddress() {
        String ip = newIp();
        for (int i = 0; i < 20; i++) {
            problem(
                    post("/v1/auth/login", null, loginBody(newEmail(), "any-password-123"), ip),
                    401,
                    "invalid-credentials");
        }

        HttpResponse<String> limited = post("/v1/auth/login", null, loginBody(newEmail(), "any-password-123"), ip);

        JsonNode problem = problem(limited, 429, "rate-limited");
        assertThat(problem.path("detail").asString()).isEqualTo("Too many login attempts. Retry later.");
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void loginIsRateLimitedPerAccount() {
        String email = newEmail();
        for (int i = 0; i < 10; i++) {
            problem(
                    post("/v1/auth/login", null, loginBody(email, "any-password-123"), newIp()),
                    401,
                    "invalid-credentials");
        }

        problem(post("/v1/auth/login", null, loginBody(email, "any-password-123"), newIp()), 429, "rate-limited");
    }

    // API 3

    @Test
    void refreshRotatesTheTokenAndPicksUpANewTeam() {
        Account account = signUp();
        JsonNode first = logIn(account);
        TeamId team = ApiTestFixtures.insertTeam();
        setTeam(account.id(), team);

        HttpResponse<String> response = post("/v1/auth/refresh", null, refreshBody(first), null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode second = json.readTree(response.body());
        assertThat(second.path("tokenType").asString()).isEqualTo("Bearer");
        assertThat(second.path("refreshToken").asString())
                .startsWith("rt_")
                .isNotEqualTo(first.path("refreshToken").asString());
        assertThat(second.path("teamId").asString()).isEqualTo(team.value().toString());
        HttpResponse<String> me =
                send("GET", "/v1/me", second.path("accessToken").asString(), null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).path("teamName").asString()).isNotBlank();
    }

    @Test
    void reusingARefreshTokenRevokesTheWholeFamily() {
        Account account = signUp();
        JsonNode first = logIn(account);
        JsonNode second = json.readTree(
                post("/v1/auth/refresh", null, refreshBody(first), null).body());
        assertThat(send("GET", "/v1/me", second.path("accessToken").asString(), null)
                        .statusCode())
                .isEqualTo(200);

        JsonNode reused =
                problem(post("/v1/auth/refresh", null, refreshBody(first), null), 401, "invalid-refresh-token");

        assertThat(reused.path("title").asString()).isEqualTo("Refresh failed");
        assertThat(reused.path("detail").asString())
                .isEqualTo("The refresh token is invalid, expired or already used.");
        problem(post("/v1/auth/refresh", null, refreshBody(second), null), 401, "invalid-refresh-token");
        assertThat(ApiTestFixtures.control()
                        .sql("SELECT count(*) FROM scheduler.user_sessions WHERE user_id = :u AND status <> 'REVOKED'")
                        .param("u", account.id().value())
                        .query(Long.class)
                        .single())
                .isZero();
        authenticator.invalidateAll();
        problem(send("GET", "/v1/me", second.path("accessToken").asString(), null), 401, "unauthenticated");
        problem(send("GET", "/v1/me", first.path("accessToken").asString(), null), 401, "unauthenticated");
        assertThat(audit(account.id(), "SESSIONS_REVOKED")).singleElement().satisfies(entry -> {
            assertThat(entry.get("actor")).isEqualTo("system:refresh-reuse");
            assertThat(entry.get("details").toString()).contains("refresh-token-reuse");
        });
    }

    @Test
    void anUnknownOrMalformedRefreshTokenIs401() {
        problem(
                post("/v1/auth/refresh", null, "{\"refreshToken\": \"rt_" + "A".repeat(43) + "\"}", null),
                401,
                "invalid-refresh-token");
        problem(post("/v1/auth/refresh", null, "{\"refreshToken\": \"garbage\"}", null), 401, "invalid-refresh-token");
    }

    @Test
    void aDisabledUserCannotRefresh() {
        Account account = signUp();
        JsonNode tokens = logIn(account);
        ApiTestFixtures.control()
                .sql("UPDATE scheduler.users SET status = 'DISABLED' WHERE user_id = :u")
                .param("u", account.id().value())
                .update();

        problem(post("/v1/auth/refresh", null, refreshBody(tokens), null), 401, "invalid-refresh-token");
    }

    // API 4

    @Test
    void logoutRevokesTheSession() {
        Account account = signUp();
        JsonNode tokens = logIn(account);
        String access = tokens.path("accessToken").asString();

        HttpResponse<String> response = post("/v1/auth/logout", access, refreshBody(tokens), null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        problem(post("/v1/auth/refresh", null, refreshBody(tokens), null), 401, "invalid-refresh-token");
        authenticator.invalidateAll();
        problem(send("GET", "/v1/me", access, null), 401, "unauthenticated");
    }

    @Test
    void logoutOfAnUnknownOrRevokedTokenIsStill204() {
        Account account = signUp();
        JsonNode tokens = logIn(account);
        String access = tokens.path("accessToken").asString();

        assertThat(post("/v1/auth/logout", access, "{\"refreshToken\": \"rt_unknown\"}", null)
                        .statusCode())
                .isEqualTo(204);
        assertThat(post("/v1/auth/logout", access, refreshBody(tokens), null).statusCode())
                .isEqualTo(204);
        JsonNode other = logIn(account);
        assertThat(post("/v1/auth/logout", other.path("accessToken").asString(), refreshBody(tokens), null)
                        .statusCode())
                .isEqualTo(204);
    }

    @Test
    void logoutValidatesTheBodyAndNeedsACredential() {
        JsonNode tokens = logIn(signUp());

        JsonNode problem =
                problem(post("/v1/auth/logout", tokens.path("accessToken").asString(), "{}", null), 400, "validation");
        assertThat(problem.path("errors").toString()).isEqualTo("[{\"field\":\"refreshToken\",\"code\":\"required\"}]");
        problem(post("/v1/auth/logout", null, refreshBody(tokens), null), 401, "unauthenticated");
    }

    // API 5

    @Test
    void theProfileOfAUserWithoutATeam() {
        Account account = signUp();
        JsonNode tokens = logIn(account);

        HttpResponse<String> response =
                send("GET", "/v1/me", tokens.path("accessToken").asString(), null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("userId").asString())
                .isEqualTo(account.id().value().toString());
        assertThat(body.path("email").asString()).isEqualTo(account.email());
        assertThat(body.path("teamId").isNull()).isTrue();
        assertThat(body.path("teamName").isNull()).isTrue();
        assertThat(body.path("createdAt").asString()).isNotBlank();
    }

    @Test
    void theProfileNeedsAUserAccessToken() {
        JsonNode missing = problem(send("GET", "/v1/me", null, null), 401, "unauthenticated");
        assertThat(missing.path("detail").asString()).isEqualTo("A valid access token or API key is required.");

        TeamId team = ApiTestFixtures.insertTeam();
        Caller owner = newUser(team);
        IssuedApiKey key = apiKeys.issue();
        ApiTestFixtures.insertApiKey(key.keyId(), team, owner.user(), key.secretHash(), key.pepperVersion());
        JsonNode withKey = problem(send("GET", "/v1/me", key.key().reveal(), null), 401, "unauthenticated");
        assertThat(withKey.path("detail").asString()).isEqualTo("A valid access token is required.");
        problem(
                post("/v1/me/password", key.key().reveal(), changeBody("a-current-password", password()), null),
                401,
                "unauthenticated");
    }

    // API 6

    @Test
    void changingThePasswordRevokesEveryOtherSession() {
        Account account = signUp();
        JsonNode mine = logIn(account);
        JsonNode other = logIn(account);
        String newPassword = password();

        HttpResponse<String> response = post(
                "/v1/me/password",
                mine.path("accessToken").asString(),
                changeBody(account.password(), newPassword),
                null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        problem(post("/v1/auth/refresh", null, refreshBody(other), null), 401, "invalid-refresh-token");
        assertThat(post("/v1/auth/refresh", null, refreshBody(mine), null).statusCode())
                .isEqualTo(200);
        problem(post("/v1/auth/login", null, loginBody(account), newIp()), 401, "invalid-credentials");
        assertThat(post("/v1/auth/login", null, loginBody(account.email(), newPassword), newIp())
                        .statusCode())
                .isEqualTo(200);
        assertThat(audit(account.id(), "PASSWORD_CHANGED")).singleElement().satisfies(entry -> {
            assertThat(entry.get("actor")).isEqualTo("user:" + account.id().value());
            assertThat(entry.get("details").toString()).contains("\"otherSessionsRevoked\": 1");
        });
        assertThat(audit(account.id(), "SESSIONS_REVOKED"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.get("details").toString()).contains("password-change"));
        assertThat(ApiTestFixtures.control()
                        .sql("SELECT details::text FROM scheduler.audit_log WHERE resource_id = :u")
                        .param("u", account.id().value().toString())
                        .query(String.class)
                        .list())
                .noneMatch(details -> details.contains(newPassword) || details.contains(account.password()));
    }

    @Test
    void aWrongCurrentPasswordOrAWeakNewOneIs400() {
        Account account = signUp();
        String access = logIn(account).path("accessToken").asString();

        JsonNode wrong = problem(
                post("/v1/me/password", access, changeBody("not-the-password", password()), null),
                400,
                "invalid-current-password");
        assertThat(wrong.path("title").asString()).isEqualTo("Password not changed");
        assertThat(wrong.path("detail").asString()).isEqualTo("The current password is incorrect.");

        JsonNode weak = problem(
                post("/v1/me/password", access, changeBody(account.password(), "short"), null), 400, "validation");
        assertThat(weak.path("detail").asString())
                .isEqualTo("Password must be 12 to 128 characters and not a common password.");
        assertThat(weak.path("errors").toString()).isEqualTo("[{\"field\":\"newPassword\",\"code\":\"too-short\"}]");
    }

    @Test
    void passwordChangesAreRateLimitedPerUser() {
        Account account = signUp();
        String access = logIn(account).path("accessToken").asString();
        for (int i = 0; i < 5; i++) {
            problem(
                    post("/v1/me/password", access, changeBody("not-the-password", password()), null),
                    400,
                    "invalid-current-password");
        }

        JsonNode problem = problem(
                post("/v1/me/password", access, changeBody("not-the-password", password()), null), 429, "rate-limited");
        assertThat(problem.path("detail").asString()).isEqualTo("Too many attempts. Retry later.");
    }

    // API 7

    @Test
    void jwksPublishesPublicKeysWithCachingHeaders() {
        HttpResponse<String> response = send("GET", "/.well-known/jwks.json", null, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("public, max-age=300");
        JsonNode key = json.readTree(response.body()).path("keys").path(0);
        assertThat(key.path("kty").asString()).isEqualTo("EC");
        assertThat(key.path("crv").asString()).isEqualTo("P-256");
        assertThat(key.path("alg").asString()).isEqualTo("ES256");
        assertThat(key.path("use").asString()).isEqualTo("sig");
        assertThat(key.path("kid").asString()).startsWith("k");
        assertThat(key.has("d")).isFalse();
    }

    // OpenAPI

    @Test
    void theDocumentCarriesTheLldExamples() {
        JsonNode paths =
                json.readTree(send("GET", "/v3/api-docs/v1", null, null).body()).path("paths");

        JsonNode signup = paths.path("/v1/auth/signup").path("post");
        assertThat(signup.path("security").isArray() && signup.path("security").isEmpty())
                .isTrue();
        assertThat(signup.path("responses").path("409").toString())
                .contains("An account with this email already exists.");
        assertThat(signup.path("responses").path("429").toString())
                .contains("Too many signups from this address. Retry later.");
        assertThat(paths.path("/v1/auth/login")
                        .path("post")
                        .path("responses")
                        .path("401")
                        .toString())
                .contains("Invalid email or password.");
        assertThat(paths.path("/v1/auth/refresh")
                        .path("post")
                        .path("responses")
                        .path("401")
                        .toString())
                .contains("invalid-refresh-token");
        assertThat(paths.path("/v1/auth/logout").path("post").path("responses").has("204"))
                .isTrue();
        assertThat(paths.path("/v1/me")
                        .path("get")
                        .path("responses")
                        .path("401")
                        .toString())
                .contains("A valid access token is required.");
        assertThat(paths.path("/v1/me/password")
                        .path("post")
                        .path("responses")
                        .path("400")
                        .toString())
                .contains("invalid-current-password");
        assertThat(paths.path("/.well-known/jwks.json")
                        .path("get")
                        .path("responses")
                        .path("200")
                        .toString())
                .contains("max-age=300");
    }

    // Helpers

    private Account signUp() {
        String email = newEmail();
        String password = password();
        HttpResponse<String> response = post("/v1/auth/signup", null, signupBody(email, password), newIp());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        UserId id = UserId.parse(json.readTree(response.body()).path("userId").asString());
        return new Account(id, email, password);
    }

    private JsonNode logIn(Account account) {
        HttpResponse<String> response = post("/v1/auth/login", null, loginBody(account), newIp());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> post(String path, @Nullable String bearer, String body, @Nullable String clientIp) {
        if (clientIp == null) {
            return send("POST", path, bearer, body);
        }
        return send("POST", path, bearer, body, "X-Forwarded-For", clientIp);
    }

    private static void setTeam(UserId user, TeamId team) {
        ApiTestFixtures.control()
                .sql("UPDATE scheduler.users SET team_id = :t WHERE user_id = :u")
                .param("t", team.value())
                .param("u", user.value())
                .update();
    }

    private static Map<String, Object> userRow(UserId user) {
        return ApiTestFixtures.control()
                .sql("SELECT failed_logins, locked_until FROM scheduler.users WHERE user_id = :u")
                .param("u", user.value())
                .query()
                .singleRow();
    }

    private static List<Map<String, Object>> audit(UserId user, String action) {
        return ApiTestFixtures.control()
                .sql("""
                        SELECT actor, resource_type, details::text AS details
                          FROM scheduler.audit_log
                         WHERE resource_id = :u AND action = :action
                        """)
                .param("u", user.value().toString())
                .param("action", action)
                .query()
                .listOfRows();
    }

    private String signupBody(String email, String password) {
        return json.writeValueAsString(Map.of("email", email, "password", password));
    }

    private String loginBody(Account account) {
        return loginBody(account.email(), account.password());
    }

    private String loginBody(String email, String password) {
        return json.writeValueAsString(Map.of("email", email, "password", password));
    }

    private String refreshBody(JsonNode tokens) {
        return json.writeValueAsString(
                Map.of("refreshToken", tokens.path("refreshToken").asString()));
    }

    private String changeBody(String current, String next) {
        return json.writeValueAsString(Map.of("currentPassword", current, "newPassword", next));
    }

    static String newEmail() {
        return "user-" + ApiTestFixtures.randomHex().substring(0, 16) + "@example.com";
    }

    static String password() {
        return "pw-" + ApiTestFixtures.randomHex().substring(0, 20);
    }

    // A random client address, so the per-IP buckets of different tests do not meet.
    static String newIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "198.51." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    }

    private JsonNode problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode problem = json.readTree(response.body());
        assertThat(problem.path("type").asString()).isEqualTo("/errors/" + code);
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("traceId").asString()).isNotBlank();
        return problem;
    }

    private record Account(UserId id, String email, String password) {}
}
