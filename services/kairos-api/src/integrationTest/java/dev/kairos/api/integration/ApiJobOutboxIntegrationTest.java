package dev.kairos.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.proto.v1.Dispatch;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/** Starts the public API and proves an immediate job commits a dispatch outbox record. */
class ApiJobOutboxIntegrationTest extends ApiApplicationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private CellRouter cells;

    @Test
    void immediateJobCreatesPinnedDispatchInItsCell() throws Exception {
        Caller caller = newUser(ApiTestFixtures.insertTeam());
        String endpoint = """
                {"name":"integration-target","method":"POST","urlTemplate":"https://orders.internal/v1/{orderId}",
                 "auth":{"type":"NONE"},"timeoutMs":30000,
                 "retryPolicy":{"maxRetries":0,"strategy":"FIXED","baseDelaySec":1,"maxDelaySec":1,"maxAgeSec":60},
                 "limits":{"maxConcurrency":10,"ratePerSec":20}}
                """;
        var createdEndpoint = send("POST", "/v1/endpoints", caller.token(), endpoint);
        assertThat(createdEndpoint.statusCode()).as(createdEndpoint.body()).isEqualTo(201);

        String name = "integration-" + UUID.randomUUID();
        String body = """
                {"name":"%s","endpoint":"integration-target","schedule":{"type":"IMMEDIATE"},
                 "request":{"pathParams":{"orderId":"42"}},"priority":5}
                """.formatted(name);
        var createdJob = send("POST", "/v1/jobs", caller.token(), body);
        assertThat(createdJob.statusCode()).as(createdJob.body()).isEqualTo(201);
        UUID job = UUID.fromString(JSON.readTree(createdJob.body()).path("id").asString());
        var cell = cells.forId(job).jdbc();
        UUID run = cell.sql("SELECT run_id FROM scheduler.run_inflight WHERE target_id=:job")
                .param("job", job)
                .query(UUID.class)
                .single();
        byte[] bytes = cell.sql("SELECT payload FROM scheduler.outbox WHERE run_id=:run")
                .param("run", run)
                .query(byte[].class)
                .single();
        Dispatch dispatch = Dispatch.parseFrom(bytes);
        assertThat(dispatch.getDefinitionVersion()).isEqualTo(1);
        assertThat(dispatch.getEndpointVersion()).isEqualTo(1);
        assertThat(dispatch.getRequest().getPathParamsMap()).containsEntry("orderId", "42");
    }
}
