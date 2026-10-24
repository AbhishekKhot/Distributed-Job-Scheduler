package dev.kairos.api.stats;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.domain.id.TeamId;
import dev.kairos.testsupport.TestIds;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP coverage of team counters, current DLQ size, and window validation. */
class StatsIntegrationTest extends ApiApplicationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void combinesMinuteCountersWithRetainedLatencyDistribution() throws Exception {
        TeamId team = ApiTestFixtures.insertTeam();
        Caller caller = newUser(team);
        UUID endpoint = TestIds.endpointId().value();
        UUID job = TestIds.jobId(9).value();
        Instant now = Instant.now();
        Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
        ApiTestFixtures.control()
                .sql("""
                        INSERT INTO scheduler.api_endpoints
                            (endpoint_id, team_id, name, version, spec, created_at, updated_at)
                        VALUES (:id, :team, :name, 1, '{}'::jsonb, :now, :now)
                        """)
                .param("id", endpoint)
                .param("team", team.value())
                .param("name", "stats-" + endpoint.toString().substring(0, 8))
                .param("now", ApiTestFixtures.utc(now))
                .update();
        ApiTestFixtures.cell(0)
                .sql("""
                        INSERT INTO scheduler.team_stats_minute
                            (team_id, minute, node_id, attempts, successes, dead_lettered, latency_sum_ms)
                        VALUES (:team, :minute, 'stats-test', 3, 2, 1, 600)
                        """)
                .param("team", team.value())
                .param("minute", ApiTestFixtures.utc(minute))
                .update();
        ApiTestFixtures.cell(0)
                .sql("""
                        INSERT INTO scheduler.endpoint_health_minute
                            (endpoint_id, minute, node_id, team_id, attempts, successes, dead_lettered,
                             latency_sum_ms, latency_max_ms)
                        VALUES (:endpoint, :minute, 'stats-test', :team, 3, 2, 1, 600, 300)
                        """)
                .param("endpoint", endpoint)
                .param("minute", ApiTestFixtures.utc(minute))
                .param("team", team.value())
                .update();
        insertHistory(team, job, endpoint, now, 100, 1);
        insertHistory(team, job, endpoint, now, 200, 2);
        insertHistory(team, job, endpoint, now, 300, 3);
        insertDlq(team, job, endpoint, now);

        JsonNode stats = json.readTree(
                send("GET", "/v1/stats?window=24h", caller.token(), null).body());
        assertThat(stats.path("attempts").asLong()).isEqualTo(3);
        assertThat(stats.path("successRate").asDouble())
                .isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.00001));
        assertThat(stats.path("p50LatencyMs").asLong()).isEqualTo(200);
        assertThat(stats.path("p95LatencyMs").asLong()).isEqualTo(300);
        assertThat(stats.path("deadLettered").asLong()).isEqualTo(1);
        assertThat(stats.path("openDlq").asLong()).isEqualTo(1);
        assertThat(stats.path("byEndpoint").path(0).path("endpoint").asString())
                .isEqualTo("stats-" + endpoint.toString().substring(0, 8));
        assertThat(stats.path("byEndpoint").path(0).path("p95LatencyMs").asLong())
                .isEqualTo(300);
    }

    @Test
    void rejectsWindowsOutsideOneHourThroughSevenDays() {
        Caller caller = newUser(ApiTestFixtures.insertTeam());
        assertThat(send("GET", "/v1/stats?window=8d", caller.token(), null).statusCode())
                .isEqualTo(400);
    }

    private static void insertHistory(TeamId team, UUID job, UUID endpoint, Instant now, int latency, int attempt) {
        ApiTestFixtures.cell(0)
                .sql("""
                        INSERT INTO scheduler.run_history
                            (scheduled_at, run_id, attempt, infra_attempt, team_id, target_type, definition_id,
                             target_id, endpoint_id, trigger, outcome, final, finished_at, latency_ms)
                        VALUES (:scheduled, :run, :attempt, 0, :team, 1, :job, :job, :endpoint, 3, 1, true,
                                :finished, :latency)
                        """)
                .param("scheduled", ApiTestFixtures.utc(now.minusSeconds(attempt)))
                .param("run", TestIds.runId(9).value())
                .param("attempt", attempt)
                .param("team", team.value())
                .param("job", job)
                .param("endpoint", endpoint)
                .param("finished", ApiTestFixtures.utc(now))
                .param("latency", latency)
                .update();
    }

    private static void insertDlq(TeamId team, UUID job, UUID endpoint, Instant now) {
        ApiTestFixtures.cell(0)
                .sql("""
                        INSERT INTO scheduler.dead_letters
                            (dlq_id, team_id, run_id, shard_id, scheduled_at, target_type, definition_id,
                             definition_version, target_id, endpoint_id, endpoint_version, attempt, infra_attempt,
                             priority, reason, status, fence, created_at)
                        VALUES (:id, :team, :run, 9, :now, 1, :job, 1, :job, :endpoint, 1, 1, 0, 5, 1, 1, 8, :now)
                        """)
                .param("id", TestIds.dlqId(9).value())
                .param("team", team.value())
                .param("run", TestIds.runId(9).value())
                .param("now", ApiTestFixtures.utc(now))
                .param("job", job)
                .param("endpoint", endpoint)
                .update();
    }
}
