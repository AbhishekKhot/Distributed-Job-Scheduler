package dev.kairos.api.team;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.security.BearerAuthenticator;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.id.UserId;
import dev.kairos.security.apikey.ApiKeyService;
import dev.kairos.security.apikey.IssuedApiKey;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.SealedKeyset;
import dev.kairos.security.keyset.TeamSigningKey;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
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
 * The team APIs (LLD section 3.4, APIs 8 to 12) over real HTTP against Postgres and Redis: every success and error
 * row, the keysets and audit entries, cursor pagination, and session revocation of removed members.
 *
 * <p>Uses the same properties as the accounts test, so both share one application context; the loopback address is
 * a trusted proxy, so each signup sends its own {@code X-Forwarded-For} address.
 */
@TestPropertySource(properties = "kairos.api.trusted-proxies=127.0.0.1/32,::1/128")
class TeamIntegrationTest extends ApiApplicationTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private BearerAuthenticator authenticator;

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private KeysetService keysets;

    // API 8

    @Test
    void aUserWithoutATeamCreatesOneWithQuotasKeysetsAndAnAuditEntry() {
        Caller caller = newUser(null);
        String name = newName();

        HttpResponse<String> response = send("POST", "/v1/teams", caller.token(), nameBody(name));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).contains("/v1/team");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode body = json.readTree(response.body());
        TeamId team = TeamId.parse(body.path("teamId").asString());
        assertThat(body.path("name").asString()).isEqualTo(name);
        assertThat(body.path("quotas").toString())
                .isEqualTo("{\"maxJobs\":10000,\"maxExecPerSec\":2000,\"maxInflight\":2000,"
                        + "\"maxWorkflowTasks\":200,\"backfillPerSec\":100,\"redrivePerSec\":100}");
        assertThat(body.path("createdAt").asString()).endsWith("Z");
        String signingKey = body.path("signingKey").asString();
        assertThat(signingKey).startsWith("ksig_v1_").hasSize("ksig_v1_".length() + 43);

        Map<String, Object> row = ApiTestFixtures.control()
                .sql("SELECT slug, quotas::text AS quotas FROM scheduler.teams WHERE team_id = :t")
                .param("t", team.value())
                .query()
                .singleRow();
        assertThat(row.get("slug")).isEqualTo(name);
        assertThat((String) row.get("quotas")).contains("\"maxJobs\": 10000", "\"redrivePerSec\": 100");
        assertThat(teamOf(caller.user())).isEqualTo(team.value());

        List<Map<String, Object>> stored = ApiTestFixtures.control()
                .sql("""
                        SELECT purpose, encrypted_keyset, kek_uri
                          FROM scheduler.team_keysets
                         WHERE team_id = :t
                         ORDER BY purpose
                        """)
                .param("t", team.value())
                .query()
                .listOfRows();
        assertThat(stored).extracting(r -> r.get("purpose")).containsExactly("OUTPUTS", "SECRETS", "SIGNING");
        Map<String, Object> signing = stored.get(2);
        SealedKeyset sealed =
                new SealedKeyset((byte[]) signing.get("encrypted_keyset"), (String) signing.get("kek_uri"));
        // The stored keyset opens for this team only, holds version 1, and never contains the key in clear.
        TeamSigningKey opened = keysets.signingKey(team, sealed);
        assertThat(opened.version()).isEqualTo(1);
        byte[] keyBytes = Base64.getUrlDecoder().decode(signingKey.substring("ksig_v1_".length()));
        assertThat(indexOf(sealed.ciphertext(), keyBytes)).isNegative();

        List<Map<String, Object>> audit = audit(team, "TEAM_CREATED");
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor"))
                .isEqualTo("user:" + caller.user().value());
        assertThat(audit.getFirst().get("resource_type")).isEqualTo("team");
        assertThat(audit.getFirst().get("resource_id")).isEqualTo(team.value().toString());
        assertThat((String) audit.getFirst().get("details"))
                .contains("\"name\": \"" + name + "\"")
                .doesNotContain(signingKey.substring(8));
    }

    @Test
    void afterSignupLoginAndRefreshTheNewTeamIsReadable() {
        String email = "lead-" + ApiTestFixtures.randomHex().substring(0, 16) + "@example.com";
        String password = "pw-" + ApiTestFixtures.randomHex().substring(0, 20);
        String credentials = json.writeValueAsString(Map.of("email", email, "password", password));
        String ip = newIp();
        assertThat(send("POST", "/v1/auth/signup", null, credentials, "X-Forwarded-For", ip)
                        .statusCode())
                .isEqualTo(201);
        JsonNode login = json.readTree(send("POST", "/v1/auth/login", null, credentials, "X-Forwarded-For", ip)
                .body());
        String accessToken = login.path("accessToken").asString();
        problem(send("GET", "/v1/team", accessToken, null), 403, "no-team");

        String name = newName();
        assertThat(send("POST", "/v1/teams", accessToken, nameBody(name)).statusCode())
                .isEqualTo(201);
        HttpResponse<String> refreshed = send(
                "POST",
                "/v1/auth/refresh",
                null,
                json.writeValueAsString(
                        Map.of("refreshToken", login.path("refreshToken").asString())));
        assertThat(refreshed.statusCode()).as(refreshed.body()).isEqualTo(200);
        JsonNode tokens = json.readTree(refreshed.body());
        assertThat(tokens.path("teamId").isNull()).isFalse();

        // The session check caches (user, session) for up to 30s with the team it saw (none) when this login
        // created the team, so a refreshed token carrying the team is refused until that entry expires. Drop the
        // cache instead of waiting; see the T-201 follow-up in the T-203a notes.
        authenticator.invalidateAll();
        HttpResponse<String> team =
                send("GET", "/v1/team", tokens.path("accessToken").asString(), null);

        assertThat(team.statusCode()).as(team.body()).isEqualTo(200);
        assertThat(json.readTree(team.body()).path("name").asString()).isEqualTo(name);
    }

    @Test
    void anInvalidNameIs400WithTheLldMessage() {
        Caller caller = newUser(null);
        for (String bad : new String[] {"Payments", "ab", "1team", "team_x", "a".repeat(64), "", "team name"}) {
            // One caller per name: the per-user rate limit (5 per hour) is taken before the name is checked.
            JsonNode problem =
                    problem(send("POST", "/v1/teams", newUser(null).token(), nameBody(bad)), 400, "validation");
            assertThat(problem.path("detail").asString()).isEqualTo("Team name must match ^[a-z][a-z0-9-]{2,62}$.");
            assertThat(problem.path("errors").toString()).isEqualTo("[{\"field\":\"name\",\"code\":\"pattern\"}]");
        }
        JsonNode missing = problem(send("POST", "/v1/teams", caller.token(), "{}"), 400, "validation");
        assertThat(missing.path("errors").toString()).contains("\"field\":\"name\"", "\"code\":\"required\"");
        JsonNode unknown = problem(
                send("POST", "/v1/teams", caller.token(), "{\"name\": \"" + newName() + "\", \"quotas\": {}}"),
                400,
                "validation");
        assertThat(unknown.path("errors").toString()).contains("unknown-field");
        assertThat(teamOf(caller.user())).isNull();
    }

    @Test
    void aMemberOrAnApiKeyGets409AlreadyInTeam() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller member = newUser(team);

        JsonNode problem =
                problem(send("POST", "/v1/teams", member.token(), nameBody(newName())), 409, "already-in-team");
        assertThat(problem.path("detail").asString()).isEqualTo("The user already belongs to a team.");
        problem(send("POST", "/v1/teams", apiKey(team, member.user()), nameBody(newName())), 409, "already-in-team");
    }

    @Test
    void aTakenNameIs409AndLeavesNothingBehind() {
        String name = newName();
        assertThat(send("POST", "/v1/teams", newUser(null).token(), nameBody(name))
                        .statusCode())
                .isEqualTo(201);
        Caller second = newUser(null);

        JsonNode problem = problem(send("POST", "/v1/teams", second.token(), nameBody(name)), 409, "team-name-taken");

        assertThat(problem.path("detail").asString()).isEqualTo("A team with this name already exists.");
        assertThat(teamOf(second.user())).isNull();
        assertThat(ApiTestFixtures.control()
                        .sql("SELECT count(*) FROM scheduler.teams WHERE slug = :s")
                        .param("s", name)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void teamCreationIsRateLimitedPerUser() {
        Caller member = newUser(ApiTestFixtures.insertTeam());
        for (int i = 0; i < 5; i++) {
            problem(send("POST", "/v1/teams", member.token(), nameBody(newName())), 409, "already-in-team");
        }

        HttpResponse<String> limited = send("POST", "/v1/teams", member.token(), nameBody(newName()));

        JsonNode problem = problem(limited, 429, "rate-limited");
        assertThat(problem.path("detail").asString()).isEqualTo("Too many teams created. Retry later.");
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(send("POST", "/v1/teams", newUser(null).token(), nameBody(newName()))
                        .statusCode())
                .isEqualTo(201);
    }

    // API 9

    @Test
    void theTeamCarriesItsQuotasAndUsageSummedOverTheCells() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller member = newUser(team);
        ApiTestFixtures.control()
                .sql("UPDATE scheduler.teams SET quotas = CAST(:q AS jsonb) WHERE team_id = :t")
                .param("q", "{\"maxJobs\": 50, \"maxInflight\": \"lots\"}")
                .param("t", team.value())
                .update();
        insertJob(0, team, "ACTIVE");
        insertJob(1, team, "PAUSED");
        insertJob(1, team, "DELETED");
        insertJob(0, ApiTestFixtures.insertTeam(), "ACTIVE");
        insertRun(0, team, 3);
        insertRun(1, team, 3);
        insertRun(1, team, 2);
        // The reader takes the last complete minute; both candidate minutes are filled so a minute boundary
        // during the test cannot change the result. 300 + 300 attempts over two nodes and cells = 10/s.
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        for (Instant at : List.of(minute, minute.minus(1, ChronoUnit.MINUTES))) {
            insertStats(0, team, at, "node-a", 300);
            insertStats(1, team, at, "node-b", 300);
        }

        HttpResponse<String> response = send("GET", "/v1/team", member.token(), null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("teamId").asString()).isEqualTo(team.value().toString());
        assertThat(body.path("name").asString()).startsWith("t");
        assertThat(body.path("quotas").path("maxJobs").asInt()).isEqualTo(50);
        // An unusable stored value falls back to the default.
        assertThat(body.path("quotas").path("maxInflight").asInt()).isEqualTo(2000);
        assertThat(body.path("usage").toString()).isEqualTo("{\"jobs\":2,\"execPerSec\":10,\"inflight\":2}");
        assertThat(body.path("createdAt").asString()).endsWith("Z");

        HttpResponse<String> withKey = send("GET", "/v1/team", apiKey(team, member.user()), null);
        assertThat(withKey.statusCode()).as(withKey.body()).isEqualTo(200);
    }

    @Test
    void aUserWithoutATeamGets403NoTeam() {
        Caller caller = newUser(null);

        JsonNode problem = problem(send("GET", "/v1/team", caller.token(), null), 403, "no-team");
        assertThat(problem.path("detail").asString()).isEqualTo("Create a team or ask a member to add this user.");
        problem(send("GET", "/v1/team/members", caller.token(), null), 403, "no-team");
        problem(send("POST", "/v1/team/members", caller.token(), emailBody("a@example.com")), 403, "no-team");
        problem(send("GET", "/v1/team", null, null), 401, "unauthenticated");
    }

    // API 10

    @Test
    void membersArePagedNewestFirstWithAnAuthenticatedCursor() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller first = newUser(team);
        UserId second = ApiTestFixtures.insertUser(team);
        UserId third = ApiTestFixtures.insertUser(team);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        setCreatedAt(first.user(), base);
        setCreatedAt(second, base.plusSeconds(60));
        setCreatedAt(third, base.plusSeconds(120));
        newUser(ApiTestFixtures.insertTeam());

        JsonNode page1 = ok(send("GET", "/v1/team/members?limit=2", first.token(), null));

        assertThat(ids(page1))
                .containsExactly(third.value().toString(), second.value().toString());
        assertThat(page1.path("items").path(0).path("email").asString()).isEqualTo(emailOf(third));
        assertThat(page1.path("items").path(0).path("addedAt").asString()).isEqualTo("2026-01-01T00:02:00Z");
        String cursor = page1.path("nextCursor").asString();
        assertThat(cursor).isNotBlank();

        JsonNode page2 = ok(send("GET", "/v1/team/members?limit=2&cursor=" + cursor, first.token(), null));
        assertThat(ids(page2)).containsExactly(first.user().value().toString());
        assertThat(page2.path("nextCursor").isNull()).isTrue();

        JsonNode all = ok(send("GET", "/v1/team/members", apiKey(team, first.user()), null));
        assertThat(ids(all)).hasSize(3);
        assertThat(all.has("nextCursor")).isTrue();
    }

    @Test
    void badLimitsAndForeignOrTamperedCursorsAre400() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);
        ApiTestFixtures.insertUser(team);
        String cursor = ok(send("GET", "/v1/team/members?limit=1", caller.token(), null))
                .path("nextCursor")
                .asString();
        Caller other = newUser(ApiTestFixtures.insertTeam());

        for (String limit : new String[] {"0", "201"}) {
            JsonNode problem =
                    problem(send("GET", "/v1/team/members?limit=" + limit, caller.token(), null), 400, "validation");
            assertThat(problem.path("errors").toString()).isEqualTo("[{\"field\":\"limit\",\"code\":\"range\"}]");
        }
        JsonNode foreign =
                problem(send("GET", "/v1/team/members?cursor=" + cursor, other.token(), null), 400, "validation");
        assertThat(foreign.path("errors").toString()).isEqualTo("[{\"field\":\"cursor\",\"code\":\"format\"}]");
        String tampered = (cursor.charAt(0) == 'A' ? "B" : "A") + cursor.substring(1);
        problem(send("GET", "/v1/team/members?cursor=" + tampered, caller.token(), null), 400, "validation");
    }

    // API 11

    @Test
    void aMemberAddsAUserWithoutATeamByEmail() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);
        UserId added = ApiTestFixtures.insertUser(null);
        String email = emailOf(added);

        HttpResponse<String> response =
                send("POST", "/v1/team/members", caller.token(), emailBody(email.toUpperCase(java.util.Locale.ROOT)));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("userId").asString()).isEqualTo(added.value().toString());
        assertThat(body.path("email").asString()).isEqualTo(email);
        assertThat(body.path("addedAt").asString()).endsWith("Z");
        assertThat(teamOf(added)).isEqualTo(team.value());
        List<Map<String, Object>> audit = audit(team, "MEMBER_ADDED");
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor"))
                .isEqualTo("user:" + caller.user().value());
        assertThat(audit.getFirst().get("resource_type")).isEqualTo("user");
        assertThat(audit.getFirst().get("resource_id")).isEqualTo(added.value().toString());
        assertThat((String) audit.getFirst().get("details")).doesNotContain(email);

        // Adding the same member again changes nothing and is not audited twice.
        HttpResponse<String> again = send("POST", "/v1/team/members", caller.token(), emailBody(email));
        assertThat(again.statusCode()).as(again.body()).isEqualTo(201);
        assertThat(audit(team, "MEMBER_ADDED")).hasSize(1);
    }

    @Test
    void addingAnUnknownOrTakenOrMalformedEmailFails() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);
        UserId elsewhere = ApiTestFixtures.insertUser(ApiTestFixtures.insertTeam());

        JsonNode unknown = problem(
                send("POST", "/v1/team/members", caller.token(), emailBody("nobody-" + newName() + "@example.com")),
                404,
                "user-not-found");
        assertThat(unknown.path("detail").asString()).isEqualTo("No account exists for this email.");

        JsonNode taken = problem(
                send("POST", "/v1/team/members", caller.token(), emailBody(emailOf(elsewhere))),
                409,
                "user-in-another-team");
        assertThat(taken.path("detail").asString()).isEqualTo("The user already belongs to another team.");
        assertThat(teamOf(elsewhere)).isNotEqualTo(team.value());

        JsonNode malformed =
                problem(send("POST", "/v1/team/members", caller.token(), emailBody("not-an-email")), 400, "validation");
        assertThat(malformed.path("detail").asString()).isEqualTo("One or more fields are invalid.");
        assertThat(malformed.path("errors").toString()).isEqualTo("[{\"field\":\"email\",\"code\":\"format\"}]");
        JsonNode missing = problem(send("POST", "/v1/team/members", caller.token(), "{}"), 400, "validation");
        assertThat(missing.path("errors").toString()).contains("\"field\":\"email\"", "\"code\":\"required\"");
    }

    @Test
    void apiKeysCannotManageMembers() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);
        Caller other = newUser(team);
        String key = apiKey(team, caller.user());

        JsonNode add = problem(
                send("POST", "/v1/team/members", key, emailBody(emailOf(ApiTestFixtures.insertUser(null)))),
                403,
                "forbidden-for-api-key");
        assertThat(add.path("detail").asString()).isEqualTo("This operation requires a user access token.");
        problem(send("DELETE", "/v1/team/members/" + other.user().value(), key, null), 403, "forbidden-for-api-key");
        assertThat(teamOf(other.user())).isEqualTo(team.value());
    }

    @Test
    void memberAdditionsAreRateLimitedPerUser() {
        Caller caller = newUser(ApiTestFixtures.insertTeam());
        for (int i = 0; i < 30; i++) {
            problem(
                    send(
                            "POST",
                            "/v1/team/members",
                            caller.token(),
                            emailBody("nobody-" + i + newName() + "@example.com")),
                    404,
                    "user-not-found");
        }

        HttpResponse<String> limited =
                send("POST", "/v1/team/members", caller.token(), emailBody("late-" + newName() + "@example.com"));

        JsonNode problem = problem(limited, 429, "rate-limited");
        assertThat(problem.path("detail").asString()).isEqualTo("Too many members added. Retry later.");
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    // API 12

    @Test
    void removingAMemberClearsTheTeamRevokesSessionsAndAudits() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);
        Caller removed = newUser(team);
        ApiTestFixtures.insertSession(removed.user());
        assertThat(send("GET", "/v1/team", removed.token(), null).statusCode()).isEqualTo(200);

        HttpResponse<String> response =
                send("DELETE", "/v1/team/members/" + removed.user().value(), caller.token(), null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(teamOf(removed.user())).isNull();
        assertThat(activeSessions(removed.user())).isZero();
        assertThat(activeSessions(caller.user())).isEqualTo(1L);
        List<Map<String, Object>> audit = audit(team, "MEMBER_REMOVED");
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor"))
                .isEqualTo("user:" + caller.user().value());
        assertThat(audit.getFirst().get("resource_id"))
                .isEqualTo(removed.user().value().toString());
        assertThat((String) audit.getFirst().get("details")).isEqualTo("{\"sessionsRevoked\": 2}");
        assertThat((String) audit(team, "SESSIONS_REVOKED").getFirst().get("details"))
                .isEqualTo("{\"reason\": \"member-removed\", \"sessions\": 2}");

        // The cache keeps a session for at most 30s (proven in TeamSessionExpiryIntegrationTest); drop it here.
        authenticator.invalidateAll();
        problem(send("GET", "/v1/team", removed.token(), null), 401, "unauthenticated");
        assertThat(send("GET", "/v1/team", caller.token(), null).statusCode()).isEqualTo(200);
    }

    @Test
    void aMemberLeavesWithTheirOwnIdButTheLastMemberCannot() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller staying = newUser(team);
        Caller leaving = newUser(team);

        assertThat(send("DELETE", "/v1/team/members/" + leaving.user().value(), leaving.token(), null)
                        .statusCode())
                .isEqualTo(204);

        assertThat(teamOf(leaving.user())).isNull();
        List<Map<String, Object>> left = audit(team, "MEMBER_LEFT");
        assertThat(left).hasSize(1);
        assertThat(left.getFirst().get("actor"))
                .isEqualTo("user:" + leaving.user().value());
        assertThat((String) audit(team, "SESSIONS_REVOKED").getFirst().get("details"))
                .contains("member-left");

        JsonNode last = problem(
                send("DELETE", "/v1/team/members/" + staying.user().value(), staying.token(), null),
                409,
                "last-member");
        assertThat(last.path("detail").asString()).isEqualTo("A team must keep at least one member.");
        assertThat(teamOf(staying.user())).isEqualTo(team.value());
    }

    @Test
    void removingANonMemberIs404AndAMalformedIdIs400() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);
        newUser(team);
        UserId foreign = ApiTestFixtures.insertUser(ApiTestFixtures.insertTeam());

        for (UUID id : List.of(foreign.value(), UUID.randomUUID())) {
            JsonNode problem =
                    problem(send("DELETE", "/v1/team/members/" + id, caller.token(), null), 404, "not-found");
            assertThat(problem.path("detail").asString()).isEqualTo("The member does not exist.");
        }
        assertThat(teamOf(foreign)).isNotNull();
        problem(send("DELETE", "/v1/team/members/not-a-uuid", caller.token(), null), 400, "validation");
    }

    // OpenAPI

    @Test
    void theDocumentCarriesTheLldExamples() {
        JsonNode paths =
                json.readTree(send("GET", "/v3/api-docs/v1", null, null).body()).path("paths");

        JsonNode create = paths.path("/v1/teams").path("post").path("responses");
        assertThat(create.path("201").toString()).contains("signingKey");
        assertThat(create.path("409").toString()).contains("already-in-team", "team-name-taken");
        assertThat(create.path("429").toString()).contains("Too many teams created. Retry later.");
        assertThat(create.path("403").isMissingNode()).isTrue();
        assertThat(paths.path("/v1/team")
                        .path("get")
                        .path("responses")
                        .path("403")
                        .toString())
                .contains("/errors/no-team");
        assertThat(paths.path("/v1/team/members").path("get").path("parameters").toString())
                .contains("\"name\":\"limit\"", "\"name\":\"cursor\"");
        JsonNode add = paths.path("/v1/team/members").path("post").path("responses");
        assertThat(add.path("403").toString()).contains("forbidden-for-api-key", "no-team");
        assertThat(add.path("404").toString()).contains("user-not-found");
        assertThat(add.path("409").toString()).contains("user-in-another-team");
        JsonNode remove = paths.path("/v1/team/members/{userId}").path("delete").path("responses");
        assertThat(remove.has("204")).isTrue();
        assertThat(remove.path("409").toString()).contains("last-member");
        assertThat(remove.path("403").toString()).contains("forbidden-for-api-key");
    }

    // Helpers

    private String apiKey(TeamId team, UserId owner) {
        IssuedApiKey key = apiKeys.issue();
        ApiTestFixtures.insertApiKey(key.keyId(), team, owner, key.secretHash(), key.pepperVersion());
        return key.key().reveal();
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("userId").asString()));
        return ids;
    }

    private String nameBody(String name) {
        return json.writeValueAsString(Map.of("name", name));
    }

    private String emailBody(String email) {
        return json.writeValueAsString(Map.of("email", email));
    }

    static String newName() {
        return "team-" + ApiTestFixtures.randomHex().substring(0, 16);
    }

    static String newIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "203.0." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    }

    static @Nullable UUID teamOf(UserId user) {
        return ApiTestFixtures.control()
                .sql("SELECT team_id FROM scheduler.users WHERE user_id = :u")
                .param("u", user.value())
                .query((rs, row) -> rs.getObject("team_id", UUID.class))
                .optional()
                .orElse(null);
    }

    private static String emailOf(UserId user) {
        return ApiTestFixtures.control()
                .sql("SELECT email FROM scheduler.users WHERE user_id = :u")
                .param("u", user.value())
                .query(String.class)
                .single();
    }

    private static void setCreatedAt(UserId user, Instant at) {
        ApiTestFixtures.control()
                .sql("UPDATE scheduler.users SET created_at = :at WHERE user_id = :u")
                .param("at", ApiTestFixtures.utc(at))
                .param("u", user.value())
                .update();
    }

    private static long activeSessions(UserId user) {
        return ApiTestFixtures.control()
                .sql("SELECT count(*) FROM scheduler.user_sessions WHERE user_id = :u AND status = 'ACTIVE'")
                .param("u", user.value())
                .query(Long.class)
                .single();
    }

    private static List<Map<String, Object>> audit(TeamId team, String action) {
        return ApiTestFixtures.control()
                .sql("""
                        SELECT actor, resource_type, resource_id, details::text AS details
                          FROM scheduler.audit_log
                         WHERE team_id = :t AND action = :action
                         ORDER BY at
                        """)
                .param("t", team.value())
                .param("action", action)
                .query()
                .listOfRows();
    }

    private static void insertJob(int cell, TeamId team, String status) {
        Instant now = Instant.now();
        ApiTestFixtures.cell(cell)
                .sql("""
                        INSERT INTO scheduler.jobs
                            (job_id, team_id, name, version, status, endpoint_id, spec, created_by, created_at,
                             updated_at)
                        VALUES (:id, :t, :name, 1, :status, :endpoint, '{}'::jsonb, 'user:test', :now, :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("t", team.value())
                .param("name", "job-" + ApiTestFixtures.randomHex().substring(0, 12))
                .param("status", status)
                .param("endpoint", UUID.randomUUID())
                .param("now", ApiTestFixtures.utc(now))
                .update();
    }

    private static void insertRun(int cell, TeamId team, int state) {
        Instant now = Instant.now();
        ApiTestFixtures.cell(cell)
                .sql("""
                        INSERT INTO scheduler.run_inflight
                            (shard_id, run_id, team_id, target_type, definition_id, definition_version, target_id,
                             endpoint_id, endpoint_version, scheduled_at, due_at, state, series_started_at, priority,
                             trigger)
                        VALUES (:shard, :run, :t, 1, :def, 1, :def, :endpoint, 1, :now, :now, :state, :now, 5, 1)
                        """)
                .param("shard", (short) (cell * 128 + 1))
                .param("run", UUID.randomUUID())
                .param("t", team.value())
                .param("def", UUID.randomUUID())
                .param("endpoint", UUID.randomUUID())
                .param("now", ApiTestFixtures.utc(now))
                .param("state", (short) state)
                .update();
    }

    private static void insertStats(int cell, TeamId team, Instant minute, String node, int attempts) {
        ApiTestFixtures.cell(cell)
                .sql("""
                        INSERT INTO scheduler.team_stats_minute (team_id, minute, node_id, attempts)
                        VALUES (:t, :minute, :node, :attempts)
                        """)
                .param("t", team.value())
                .param("minute", ApiTestFixtures.utc(minute))
                .param("node", node)
                .param("attempts", attempts)
                .update();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        String text = new String(haystack, StandardCharsets.ISO_8859_1);
        return text.indexOf(new String(needle, StandardCharsets.ISO_8859_1));
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
