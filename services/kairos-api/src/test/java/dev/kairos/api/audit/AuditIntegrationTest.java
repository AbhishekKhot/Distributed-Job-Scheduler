package dev.kairos.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.audit.application.AuditWriter;
import dev.kairos.api.support.paging.CursorCodec;
import dev.kairos.domain.id.TeamId;
import dev.kairos.persistence.tx.ControlTransactions;
import dev.kairos.security.apikey.ApiKeyService;
import dev.kairos.security.apikey.IssuedApiKey;
import dev.kairos.testsupport.MutableClock;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Audit API isolation, filters, stable cursors, nullable metadata and shared transaction participation. */
@Import(AuditIntegrationTest.TestClock.class)
class AuditIntegrationTest extends ApiApplicationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private AuditWriter writer;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ApiKeyService keys;

    @Autowired
    private CursorCodec cursors;

    @Autowired
    private ControlTransactions transactions;

    @Test
    void listsOnlyTheCallersTeamAndWorksWithAnApiKey() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller user = newUser(team);
        event(team, "JOB_DELETED", "own");
        event(ApiTestFixtures.insertTeam(), "JOB_DELETED", "foreign");
        writer.record(new AuditWriter.Entry(null, "system:test", "LOGIN_LOCKED", null, null, Map.of()));
        JsonNode page = get(user, "");
        assertThat(page.path("items").size()).isEqualTo(1);
        JsonNode entry = page.path("items").path(0);
        assertThat(entry.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("at", "actor", "action", "resourceType", "resourceId", "details");
        assertThat(entry.path("details").path("name").asString()).isEqualTo("own");
        IssuedApiKey key = keys.issue();
        ApiTestFixtures.insertApiKey(key.keyId(), team, user.user(), key.secretHash(), key.pepperVersion());
        assertThat(send("GET", "/v1/audit-log", key.key().reveal(), null).statusCode())
                .isEqualTo(200);
        assertThat(send("GET", "/v1/audit-log", null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/v1/audit-log", newUser(null).token(), null).statusCode())
                .isEqualTo(403);
    }

    @Test
    void defaultWindowExcludesOlderEventsAndExplicitRangeAndActionAreApplied() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller user = newUser(team);
        Instant now = clock.instant();
        clock.set(now.minus(Duration.ofHours(25)));
        event(team, "JOB_DELETED", "old");
        clock.set(now);
        event(team, "JOB_DELETED", "recent");
        event(team, "JOB_CREATED", "other-action");
        assertThat(get(user, "").path("items").size()).isEqualTo(2);
        JsonNode recent = get(user, "?action=JOB_DELETED");
        assertThat(recent.path("items").size()).isEqualTo(1);
        assertThat(recent.path("items").path(0).path("details").path("name").asString())
                .isEqualTo("recent");
        JsonNode old = get(user, "?from=" + now.minus(Duration.ofHours(26)) + "&to=" + now.minus(Duration.ofHours(24)));
        assertThat(old.path("items").size()).isEqualTo(1);
        assertThat(old.path("items").path(0).path("details").path("name").asString())
                .isEqualTo("old");
    }

    @Test
    void paginationHandlesTiesAndKeepsTheOriginalFilterSnapshot() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller user = newUser(team);
        for (int i = 0; i < 5; i++) event(team, "JOB_DELETED", "job-" + i);
        event(team, "JOB_CREATED", "ignored");
        JsonNode page = get(user, "?action=JOB_DELETED&limit=2");
        List<String> seen = new ArrayList<>();
        String cursor;
        do {
            for (JsonNode event : page.path("items"))
                seen.add(event.path("details").path("name").asString());
            cursor = page.path("nextCursor").isNull()
                    ? null
                    : page.path("nextCursor").asString();
            if (cursor != null) {
                clock.advance(Duration.ofSeconds(1));
                event(team, "JOB_DELETED", "new-event-after-snapshot");
                page = get(user, "?limit=2&cursor=" + cursor);
            }
        } while (cursor != null);
        assertThat(seen).containsExactlyInAnyOrder("job-0", "job-1", "job-2", "job-3", "job-4");
        // Exercise an unfiltered continuation as well.
        JsonNode unfiltered = get(user, "?limit=1");
        assertThat(get(user, "?limit=1&cursor=" + unfiltered.path("nextCursor").asString())
                        .path("items")
                        .size())
                .isEqualTo(1);
    }

    @Test
    void cursorsCannotCrossTeamsListsOrFiltersAndMalformedPayloadsAreRejected() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller user = newUser(team);
        event(team, "JOB_DELETED", "a");
        event(team, "JOB_DELETED", "b");
        String cursor =
                get(user, "?action=JOB_DELETED&limit=1").path("nextCursor").asString();
        Caller foreign = newUser(ApiTestFixtures.insertTeam());
        assertThat(send("GET", "/v1/audit-log?cursor=" + cursor, foreign.token(), null)
                        .statusCode())
                .isEqualTo(400);
        for (String extra : List.of(
                "&action=JOB_CREATED",
                "&from=" + clock.instant(),
                "&to=" + clock.instant().minusSeconds(1))) {
            // from must differ from the cursor's default last-24h lower bound.
            assertThat(send("GET", "/v1/audit-log?cursor=" + cursor + extra, user.token(), null)
                            .statusCode())
                    .isEqualTo(400);
        }
        for (String invalid : List.of(
                "bad",
                cursor.substring(0, cursor.length() - 4) + "AAAA",
                cursors.encode("api-keys", team, List.of("wrong-list")),
                cursors.encode("audit-log", team, List.of("wrong-count")),
                cursors.encode(
                        "audit-log",
                        team,
                        List.of(
                                clock.instant().toString(),
                                clock.instant().toString(),
                                "",
                                clock.instant().toString(),
                                "invalid-uuid")),
                cursors.encode(
                        "audit-log",
                        team,
                        List.of(
                                "invalid-time",
                                clock.instant().toString(),
                                "",
                                clock.instant().toString(),
                                java.util.UUID.randomUUID().toString())))) {
            assertThat(send("GET", "/v1/audit-log?cursor=" + invalid, user.token(), null)
                            .statusCode())
                    .isEqualTo(400);
        }
    }

    @Test
    void rejectsOutOfRangeAndMalformedParametersWithoutEchoingThem() {
        Caller user = newUser(ApiTestFixtures.insertTeam());
        for (String query : List.of(
                "from=" + clock.instant().minus(Duration.ofDays(91)),
                "from=invalid",
                "to=-1000000000-01-01T00:00:00Z",
                "to=invalid",
                "from=" + "x".repeat(65),
                "to=" + clock.instant().plusSeconds(1),
                "from=" + clock.instant().plusSeconds(1),
                "from=" + clock.instant() + "&to=" + clock.instant().minusSeconds(1),
                "action=",
                "action=" + "X".repeat(65),
                "action=unsafe-value",
                "limit=0",
                "limit=201",
                "limit=bad")) {
            HttpResponse<String> response = send("GET", "/v1/audit-log?" + query, user.token(), null);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("type").asString()).isEqualTo("/errors/validation");
        }
        JsonNode old = json.readTree(
                send("GET", "/v1/audit-log?from=" + clock.instant().minus(Duration.ofDays(91)), user.token(), null)
                        .body());
        assertThat(old.path("errors").path(0).path("code").asString()).isEqualTo("out-of-range");
    }

    @Test
    void sharedWriterParticipatesInTheCallersTransaction() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller user = newUser(team);
        assertThatThrownBy(() -> transactions.execute(jdbc -> {
                    event(team, "JOB_DELETED", "rolled-back");
                    throw new IllegalStateException("rollback");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(get(user, "").path("items").size()).isZero();
        transactions.execute(jdbc -> {
            event(team, "JOB_DELETED", "committed");
            return null;
        });
        assertThat(get(user, "").path("items").size()).isEqualTo(1);
    }

    @Test
    void nullableResourceMetadataIsReturnedWithoutInternalIds() {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller user = newUser(team);
        writer.record(new AuditWriter.Entry(team, "system:test", "MAINTENANCE", null, null, Map.of()));
        JsonNode row = get(user, "").path("items").path(0);
        assertThat(row.path("resourceType").isNull()).isTrue();
        assertThat(row.path("resourceId").isNull()).isTrue();
        assertThat(row.has("auditId")).isFalse();
        assertThat(row.path("details").isEmpty()).isTrue();
    }

    private void event(TeamId team, String action, String name) {
        writer.record(new AuditWriter.Entry(team, "system:test", action, "job", "test-id", Map.of("name", name)));
    }

    private JsonNode get(Caller user, String query) {
        HttpResponse<String> response = send("GET", "/v1/audit-log" + query, user.token(), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {
        @Bean
        @Primary
        MutableClock auditTestClock() {
            return MutableClock.at(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        }
    }
}
