package dev.kairos.api.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.ProbeApplicationTest;
import dev.kairos.api.security.BearerAuthenticator;
import dev.kairos.domain.id.TeamId;
import dev.kairos.security.apikey.ApiKeyService;
import dev.kairos.security.apikey.IssuedApiKey;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bearer authentication, the session check, team context and user-token-only operations over real HTTP. */
class AuthenticationIntegrationTest extends ProbeApplicationTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private BearerAuthenticator authenticator;

    @Test
    void aRequestWithoutATokenGets401WithTheProblemBody() {
        HttpResponse<String> response = send("GET", "/v1/probe/team", null, null);

        JsonNode problem = problem(response, 401, "unauthenticated");
        assertThat(problem.path("title").asString()).isEqualTo("Authentication required");
        assertThat(problem.path("detail").asString()).isEqualTo("A valid access token or API key is required.");
        assertThat(problem.path("instance").asString()).isEqualTo("/v1/probe/team");
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    void malformedOrForgedCredentialsGet401() {
        problem(send("GET", "/v1/probe/team", "not-a-token", null), 401, "unauthenticated");
        problem(send("GET", "/v1/probe/team", "kairos_short", null), 401, "unauthenticated");
        problem(
                send("GET", "/v1/probe/team", null, null, "Authorization", "Basic dXNlcjpwYXNz"),
                401,
                "unauthenticated");
    }

    @Test
    void publicOperationsIgnoreTheAuthorizationHeader() {
        HttpResponse<String> response = send("POST", "/v1/auth/refresh", "expired-or-garbage", "{}");

        assertThat(response.statusCode()).isNotEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void aUserWithoutATeamGets403NoTeamOnTeamOperations() {
        Caller caller = newUser(null);

        JsonNode problem = problem(send("GET", "/v1/probe/team", caller.token(), null), 403, "no-team");
        assertThat(problem.path("detail").asString()).isEqualTo("Create a team or ask a member to add this user.");
        HttpResponse<String> me = send("GET", "/v1/probe/me", caller.token(), null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("user:" + caller.user().value());
    }

    @Test
    void aTeamMemberGetsTheTeamFromTheToken() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);

        HttpResponse<String> response = send("GET", "/v1/probe/team", caller.token(), null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("team").asString())
                .isEqualTo(team.value().toString());
    }

    @Test
    void aRevokedSessionStopsWorking() {
        Caller caller = newUser(ApiTestFixtures.insertTeam());
        assertThat(send("GET", "/v1/probe/team", caller.token(), null).statusCode())
                .isEqualTo(200);

        ApiTestFixtures.control()
                .sql("UPDATE scheduler.user_sessions SET status = 'REVOKED' WHERE family_id = :family")
                .param("family", caller.sessionId())
                .update();
        // The cache keeps a session for at most 30s (unit-tested with a clock); drop it instead of waiting.
        authenticator.invalidateAll();

        problem(send("GET", "/v1/probe/team", caller.token(), null), 401, "unauthenticated");
    }

    @Test
    void aRemovedMemberStopsWorking() {
        Caller caller = newUser(ApiTestFixtures.insertTeam());
        assertThat(send("GET", "/v1/probe/team", caller.token(), null).statusCode())
                .isEqualTo(200);

        ApiTestFixtures.control()
                .sql("UPDATE scheduler.users SET team_id = NULL WHERE user_id = :user")
                .param("user", caller.user().value())
                .update();
        authenticator.invalidateAll();

        problem(send("GET", "/v1/probe/team", caller.token(), null), 401, "unauthenticated");
    }

    @Test
    void anApiKeyAuthenticatesForItsTeamButNotForUserOnlyOperations() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller owner = newUser(team);
        IssuedApiKey key = apiKeys.issue();
        ApiTestFixtures.insertApiKey(key.keyId(), team, owner.user(), key.secretHash(), key.pepperVersion());

        HttpResponse<String> response = send("GET", "/v1/probe/team", key.key().reveal(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("key:" + key.keyId());

        JsonNode problem =
                problem(send("POST", "/v1/probe/user-only", key.key().reveal(), null), 403, "forbidden-for-api-key");
        assertThat(problem.path("detail").asString()).isEqualTo("This operation requires a user access token.");
        assertThat(send("POST", "/v1/probe/user-only", owner.token(), null).statusCode())
                .isEqualTo(204);
    }

    @Test
    void aRevokedOrWrongApiKeyGets401() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller owner = newUser(team);
        IssuedApiKey key = apiKeys.issue();
        ApiTestFixtures.insertApiKey(key.keyId(), team, owner.user(), key.secretHash(), key.pepperVersion());
        String presented = key.key().reveal();
        String wrongSecret = presented.substring(0, presented.length() - 1)
                + (presented.charAt(presented.length() - 1) == 'A' ? 'B' : 'A');

        problem(send("GET", "/v1/probe/team", wrongSecret, null), 401, "unauthenticated");

        ApiTestFixtures.control()
                .sql("UPDATE scheduler.api_keys SET revoked_at = now() WHERE key_id = :keyId")
                .param("keyId", key.keyId())
                .update();
        authenticator.invalidateAll();
        problem(send("GET", "/v1/probe/team", presented, null), 401, "unauthenticated");
    }

    @Test
    void everyResponseCarriesTheSecurityHeadersAndARequestId() {
        HttpResponse<String> response = send("GET", "/v1/probe/team", null, null, "X-Request-Id", "client-id-42");

        assertThat(response.headers().firstValue("X-Request-Id")).contains("client-id-42");
        assertThat(response.headers().firstValue("Strict-Transport-Security")).isPresent();
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(response.headers().firstValue("Content-Security-Policy"))
                .hasValueSatisfying(policy -> assertThat(policy).startsWith("default-src 'none'"));
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();

        HttpResponse<String> invalid = send("GET", "/v1/probe/team", null, null, "X-Request-Id", "bad id;");
        assertThat(invalid.headers().firstValue("X-Request-Id"))
                .hasValueSatisfying(id -> assertThat(id).matches("[0-9a-f]{32}"));
    }

    @Test
    void swaggerUiGetsItsOwnPolicy() {
        HttpResponse<String> response = send("GET", "/swagger-ui/index.html", null, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Security-Policy"))
                .hasValueSatisfying(policy -> assertThat(policy).contains("script-src 'self'"));
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
}
