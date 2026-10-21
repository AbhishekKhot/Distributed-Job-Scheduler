package dev.kairos.api.support.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.ProbeApplicationTest;
import dev.kairos.api.ProbeController;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.shard.ShardKey;
import dev.kairos.testsupport.TestIds;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Idempotent creates over real HTTP, with records in the control database and in the owning cell. */
class IdempotencyIntegrationTest extends ProbeApplicationTest {

    private TeamId team;
    private String token;

    @BeforeEach
    void caller() {
        team = ApiTestFixtures.insertTeam();
        token = newUser(team).token();
    }

    @Test
    void aReplayReturnsTheIdenticalResponseWithoutRunningAgain() {
        String key = UUID.randomUUID().toString();
        String body = "{\"name\": \"nightly-sync\"}";

        HttpResponse<String> first = send("POST", "/v1/probe/jobs", token, body, "Idempotency-Key", key);
        int executions = ProbeController.EXECUTIONS.get();
        HttpResponse<String> second = send("POST", "/v1/probe/jobs", token, body, "Idempotency-Key", key);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers().firstValue("Location"))
                .isEqualTo(first.headers().firstValue("Location"));
        assertThat(second.headers().firstValue("ETag")).contains("\"1\"");
        assertThat(ProbeController.EXECUTIONS.get()).isEqualTo(executions);
    }

    @Test
    void theRecordLivesInTheCellOfTheNewResource() {
        String key = UUID.randomUUID().toString();
        String name = "cell-routed-job";
        send("POST", "/v1/probe/jobs", token, "{\"name\": \"" + name + "\"}", "Idempotency-Key", key);

        int cell = ShardKey.of(team.value(), name).value() < 128 ? 0 : 1;
        assertThat(count(ApiTestFixtures.cell(cell).sql(COUNT), key)).isEqualTo(1);
        assertThat(count(ApiTestFixtures.cell(1 - cell).sql(COUNT), key)).isZero();
        assertThat(count(ApiTestFixtures.control().sql(COUNT), key)).isZero();
    }

    @Test
    void aPathIdRecordLivesInTheCellOfTheId() {
        String key = UUID.randomUUID().toString();
        String id = TestIds.jobId(200).value().toString();

        HttpResponse<String> first =
                send("POST", "/v1/probe/jobs/" + id + "/runnow", token, "{}", "Idempotency-Key", key);
        HttpResponse<String> second =
                send("POST", "/v1/probe/jobs/" + id + "/runnow", token, "{}", "Idempotency-Key", key);

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(count(ApiTestFixtures.cell(1).sql(COUNT), key)).isEqualTo(1);
    }

    @Test
    void controlScopedRecordsLiveInTheControlDatabase() {
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = send("POST", "/v1/probe/batch", token, "{\"jobs\": []}", "Idempotency-Key", key);
        HttpResponse<String> second = send("POST", "/v1/probe/batch", token, "{\"jobs\": []}", "Idempotency-Key", key);

        assertThat(second.body()).isEqualTo(first.body());
        assertThat(count(ApiTestFixtures.control().sql(COUNT), key)).isEqualTo(1);
    }

    @Test
    void theSameKeyWithAnotherBodyIsAConflict() {
        String key = UUID.randomUUID().toString();
        send("POST", "/v1/probe/batch", token, "{\"jobs\": [1]}", "Idempotency-Key", key);

        HttpResponse<String> reused = send("POST", "/v1/probe/batch", token, "{\"jobs\": [2]}", "Idempotency-Key", key);

        assertThat(reused.statusCode()).isEqualTo(409);
        assertThat(reused.body())
                .contains("/errors/idempotency-key-reused")
                .contains("This Idempotency-Key was already used with a different request body.");
    }

    @Test
    void aFailedRequestFreesTheKey() {
        String key = UUID.randomUUID().toString();
        String body = "{\"name\": \"failing-job\", \"fail\": true}";

        HttpResponse<String> failed = send("POST", "/v1/probe/jobs", token, body, "Idempotency-Key", key);
        int executions = ProbeController.EXECUTIONS.get();
        HttpResponse<String> retried = send("POST", "/v1/probe/jobs", token, body, "Idempotency-Key", key);

        assertThat(failed.statusCode()).isEqualTo(409);
        assertThat(retried.statusCode()).isEqualTo(409);
        assertThat(ProbeController.EXECUTIONS.get()).isEqualTo(executions + 1);
    }

    @Test
    void concurrentRequestsWithOneKeyRunOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"name\": \"concurrent-job\"}";
        int before = ProbeController.EXECUTIONS.get();
        List<Callable<HttpResponse<String>>> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(() -> send("POST", "/v1/probe/jobs", token, body, "Idempotency-Key", key));
        }

        List<HttpResponse<String>> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<HttpResponse<String>> response : pool.invokeAll(requests)) {
                responses.add(response.get());
            }
        }

        assertThat(ProbeController.EXECUTIONS.get()).isEqualTo(before + 1);
        String created = responses.stream()
                .filter(response -> response.statusCode() == 201)
                .findFirst()
                .orElseThrow()
                .body();
        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).isIn(201, 409);
            if (response.statusCode() == 201) {
                assertThat(response.body()).isEqualTo(created);
            }
        }
    }

    @Test
    void invalidKeysAreRejected() {
        HttpResponse<String> tooLong = send("POST", "/v1/probe/batch", token, "{}", "Idempotency-Key", "k".repeat(65));

        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(tooLong.body()).contains("{\"field\":\"Idempotency-Key\",\"code\":\"length\"}");
    }

    @Test
    void requestsWithoutAKeyAreNotDeduplicated() {
        int before = ProbeController.EXECUTIONS.get();

        send("POST", "/v1/probe/batch", token, "{}");
        send("POST", "/v1/probe/batch", token, "{}");

        assertThat(ProbeController.EXECUTIONS.get()).isEqualTo(before + 2);
    }

    private static final String COUNT = "SELECT count(*) FROM scheduler.api_idempotency WHERE idem_key = :key";

    private static long count(org.springframework.jdbc.core.simple.JdbcClient.StatementSpec statement, String key) {
        return statement.param("key", key).query(Long.class).single();
    }
}
