package dev.kairos.engine.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.engine.EngineFixtures;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.TestIds;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@KairosIntegrationTest(kafka = false, redis = false)
class WorkflowInputsIntegrationTest {
    private final JsonMapper json = JsonMapper.shared();

    @Test
    void selectsOnlyReferencedOutputsAndPreservesPublicJsonTypes(KairosPostgres postgres) {
        var registry = EngineFixtures.registry(postgres);
        var jdbc = registry.cells()
                .get(dev.kairos.persistence.jdbc.Database.cellName(0))
                .jdbc();
        var team = TestIds.teamId();
        var run = TestIds.workflowRunId(12).value();
        byte[] sealed = json.writeValueAsBytes(
                Map.of("v", 1, "values", Map.of("token", "opaque-ciphertext", "unused", "unused-ciphertext")));
        jdbc.sql(
                        "INSERT INTO scheduler.task_outputs(scheduled_at,workflow_run_id,task_key,team_id,outputs,sealed,created_at) VALUES (:now,:run,'A',:team,CAST(:values AS jsonb),:sealed,:now)")
                .param("now", OffsetDateTime.parse("2026-10-25T10:00:00Z"))
                .param("run", run)
                .param("team", team.value())
                .param("values", "{\"data\":{\"list\":[1,true,null]},\"unused\":\"omit\"}")
                .param("sealed", sealed)
                .update();
        var request = json.readTree(
                "{\"headers\":{\"Authorization\":\"{{ tasks.A.outputs.token }}\"},\"body\":[\"{{ tasks.A.outputs.data }}\"]}");
        var envelope = json.readTree(WorkflowInputs.load(jdbc, team, run, request, json));
        assertThat(envelope.path("publicTasks")
                        .path("A")
                        .path("data")
                        .path("list")
                        .get(2)
                        .isNull())
                .isTrue();
        assertThat(envelope.path("tasks").path("A").path("token").asString()).isEqualTo("opaque-ciphertext");
        assertThat(envelope.toString()).doesNotContain("unused");
        assertThat(json.readTree(WorkflowInputs.load(jdbc, TestIds.teamId(), run, request, json))
                        .path("tasks")
                        .isEmpty())
                .isTrue();
        assertThat(json.readTree(WorkflowInputs.load(jdbc, team, run, json.readTree("{}"), json))
                        .path("publicTasks")
                        .isEmpty())
                .isTrue();
    }

    @Test
    void rejectsAmbiguousPublicAndEncryptedNames(KairosPostgres postgres) {
        var jdbc = EngineFixtures.registry(postgres)
                .cells()
                .get(dev.kairos.persistence.jdbc.Database.cellName(0))
                .jdbc();
        var team = TestIds.teamId();
        var run = TestIds.workflowRunId(12).value();
        jdbc.sql(
                        "INSERT INTO scheduler.task_outputs(scheduled_at,workflow_run_id,task_key,team_id,outputs,sealed,created_at) VALUES (:now,:run,'A',:team,'{\"id\":7}',:sealed,:now)")
                .param("now", OffsetDateTime.parse("2026-10-25T10:00:00Z"))
                .param("run", run)
                .param("team", team.value())
                .param("sealed", "{\"v\":1,\"values\":{\"id\":\"ciphertext\"}}".getBytes(StandardCharsets.UTF_8))
                .update();
        assertThatThrownBy(() -> WorkflowInputs.load(
                        jdbc, team, run, json.readTree("{\"body\":\"{{ tasks.A.outputs.id }}\"}"), json))
                .isInstanceOf(IllegalStateException.class);
    }
}
