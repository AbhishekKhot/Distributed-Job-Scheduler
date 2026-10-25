package dev.kairos.executor.call.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.kairos.domain.id.TeamId;
import dev.kairos.persistence.cell.CellRange;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.persistence.cell.CellStatus;
import dev.kairos.persistence.jdbc.Database;
import dev.kairos.persistence.jdbc.DatabaseOptions;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.security.cipher.SecretCipher;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Tests the executor side of workflow template pinning and public upstream-input reuse. */
@KairosIntegrationTest(kafka = false, redis = false)
class WorkflowRequestIntegrationTest {
    private static JdbcClient jdbc;
    private static JdbcExecutionSecrets secrets;

    @BeforeAll
    static void schema(KairosPostgres postgres) {
        var database = Database.of(
                "cell-0", postgres.cellOwnerDataSource(0), DatabaseOptions.defaults(), new SimpleMeterRegistry());
        jdbc = database.jdbc();
        jdbc.sql("CREATE SCHEMA IF NOT EXISTS scheduler").update();
        jdbc.sql("CREATE TABLE IF NOT EXISTS scheduler.workflows(workflow_id uuid PRIMARY KEY,team_id uuid NOT NULL)")
                .update();
        jdbc.sql(
                        "CREATE TABLE IF NOT EXISTS scheduler.workflow_versions(workflow_id uuid,version integer,spec jsonb,PRIMARY KEY(workflow_id,version))")
                .update();
        CellRouter cells = new CellRouter(
                () -> List.of(new CellRange(0, 0, 255, "cell-0", CellStatus.ACTIVE)),
                Map.of("cell-0", database),
                Duration.ofMinutes(5),
                Clock.systemUTC());
        secrets = new JdbcExecutionSecrets(
                JdbcClient.create(postgres.controlOwnerDataSource()),
                JsonMapper.builder().build(),
                mock(KeysetService.class),
                mock(SecretCipher.class),
                cells);
    }

    @Test
    void loadsTheImmutableDefinitionVersionAndRejectsOtherTeamsAndUnknownTasks() {
        UUID workflow = TestIds.workflowId(0).value();
        TeamId team = TestIds.teamId();
        jdbc.sql("INSERT INTO scheduler.workflows VALUES (:id,:team)")
                .param("id", workflow)
                .param("team", team.value())
                .update();
        for (int version : List.of(1, 2)) {
            String body =
                    "{\"tasks\":{\"root\":{\"request\":{\"headers\":{\"Authorization\":\"Bearer {{ tasks.login.outputs.token }}\"},\"body\":{\"version\":"
                            + version + "}}}}}";
            jdbc.sql("INSERT INTO scheduler.workflow_versions VALUES (:id,:version,CAST(:body AS jsonb))")
                    .param("id", workflow)
                    .param("version", version)
                    .param("body", body)
                    .update();
        }
        Dispatch dispatch = Dispatch.newBuilder()
                .setTargetTypeValue(2)
                .setDefinitionId(ProtoIds.toBytes(workflow))
                .setDefinitionVersion(1)
                .setTeamId(ProtoIds.toBytes(team.value()))
                .setTaskKey("root")
                .build();
        var request = secrets.workflowRequest(dispatch).orElseThrow();
        assertThat(request.headers()).containsEntry("Authorization", "Bearer {{ tasks.login.outputs.token }}");
        assertThat(request.body().json()).isEqualTo("{\"version\":1}");
        assertThat(secrets.workflowRequest(
                                dispatch.toBuilder().setDefinitionVersion(2).build())
                        .orElseThrow()
                        .body()
                        .json())
                .isEqualTo("{\"version\":2}");
        assertThatThrownBy(() -> secrets.workflowRequest(dispatch.toBuilder()
                        .setTeamId(ProtoIds.toBytes(TestIds.teamId().value()))
                        .build()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> secrets.workflowRequest(
                        dispatch.toBuilder().setTaskKey("unknown").build()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retainedPublicInputsPreserveJsonTypesAndRemainEligibleForUrlRendering() {
        byte[] encoded = "{\"v\":1,\"tasks\":{},\"publicTasks\":{\"root\":{\"id\":42,\"optional\":null}}}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var inputs = secrets.inputs(TestIds.teamId(), TestIds.runId(0), encoded);
        var values = inputs.get(dev.kairos.domain.model.TaskKey.of("root"));
        assertThat(values.get("id").value().json()).isEqualTo("42");
        assertThat(values.get("id").sensitive()).isFalse();
        assertThat(values.get("optional").value().json()).isEqualTo("null");
    }

    @Test
    void malformedInputEnvelopesFailBeforeRendering() {
        for (String invalid : List.of("{\"v\":2,\"tasks\":{}}", "{\"v\":1,\"tasks\":{},\"publicTasks\":[]}")) {
            assertThatThrownBy(() -> secrets.inputs(
                            TestIds.teamId(),
                            TestIds.runId(0),
                            invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
