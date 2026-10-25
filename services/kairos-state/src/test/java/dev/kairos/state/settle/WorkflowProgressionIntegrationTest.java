package dev.kairos.state.settle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.Value;
import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.Priority;
import dev.kairos.domain.model.TargetType;
import dev.kairos.domain.model.TaskKey;
import dev.kairos.domain.model.Trigger;
import dev.kairos.domain.shard.Shard;
import dev.kairos.engine.outbox.CellTransactionGuard;
import dev.kairos.engine.outbox.OutboxWriter;
import dev.kairos.engine.run.CallSpec;
import dev.kairos.engine.run.FireNotifier;
import dev.kairos.engine.run.RunCreator;
import dev.kairos.engine.run.RunFactory;
import dev.kairos.engine.run.RunInserter;
import dev.kairos.engine.run.RunTarget;
import dev.kairos.engine.run.WorkflowInputs;
import dev.kairos.engine.run.WorkflowRunCreator;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.RequestSnapshot;
import dev.kairos.proto.v1.Result;
import dev.kairos.security.cipher.SecretCipher;
import dev.kairos.security.keyset.KeyPurpose;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.LocalKeyEncryptionKey;
import dev.kairos.security.keyset.TeamAead;
import dev.kairos.state.workflow.infrastructure.JdbcWorkflowProgression;
import dev.kairos.state.workflow.infrastructure.WorkflowOutputs;
import dev.kairos.state.workflow.infrastructure.WorkflowReconciler;
import dev.kairos.testsupport.KairosIntegrationTest;
import dev.kairos.testsupport.KairosPostgres;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@KairosIntegrationTest(kafka = false, redis = false)
class WorkflowProgressionIntegrationTest {
    private SettlementFixtures f;
    private final JsonMapper json = JsonMapper.shared();
    private final SecretCipher cipher = new SecretCipher();
    private KeysetService keysets;
    private TeamAead keys;
    private JdbcWorkflowProgression progression;
    private RunFactory factory;
    private RunCreator creator;
    private UUID workflow, run;
    private JsonNode spec;
    private final String sensitive = "sensitive-" + UUID.randomUUID();

    @BeforeEach
    void setup(KairosPostgres postgres) {
        f = new SettlementFixtures(postgres);
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        keysets = new KeysetService(
                LocalKeyEncryptionKey.forLocalProfile(java.util.HexFormat.of().formatHex(bytes), true),
                new SecureRandom(),
                Duration.ofMinutes(1),
                100);
        var stored = keysets.createTeamKeyset(new TeamId(f.team), KeyPurpose.OUTPUTS);
        keys = keysets.aead(new TeamId(f.team), KeyPurpose.OUTPUTS, stored);
        f.control
                .sql(
                        "INSERT INTO scheduler.team_keysets(team_id,purpose,encrypted_keyset,kek_uri,updated_at) VALUES (:team,'OUTPUTS',:bytes,:uri,:now)")
                .param("team", f.team)
                .param("bytes", stored.ciphertext())
                .param("uri", stored.kekUri())
                .param("now", f.now())
                .update();
        f.endpointSpec(
                "{\"timeoutMs\":30000,\"outputs\":{\"id\":{\"sensitive\":false},\"secret\":{\"sensitive\":true}}}");
        var guard = new CellTransactionGuard(f.cells);
        var outbox = new OutboxWriter(guard, f.ids, f.clock);
        factory = new RunFactory(f.ids, f.clock);
        creator = new RunCreator(new RunInserter(guard), outbox, new FireNotifier(guard), f.clock);
        progression = new JdbcWorkflowProgression(
                f.cells, f.control, outbox, new WorkflowOutputs(f.control, json, keysets, cipher), json, f.clock);
    }

    private void diamond(String policy) {
        create("""
            {"onTaskFailure":"%s","reviewTimeoutSec":60,"priority":5,"tasks":{
              "A":{"endpoint":"target"},
              "B":{"endpoint":"target","dependsOn":["A"]},
              "C":{"endpoint":"target","dependsOn":["A"]},
              "D":{"endpoint":"target","dependsOn":["B","C"],"request":{"headers":{"Authorization":"Bearer {{ tasks.A.outputs.secret }}"},"body":{"id":"{{ tasks.A.outputs.id }}"}}}}}
            """.formatted(policy));
    }

    private void create(String body) {
        spec = json.readTree(body);
        workflow = f.ids.newWorkflowId(Shard.of(12)).value();
        run = f.ids.newWorkflowRunId(Shard.of(12)).value();
        f.cell(run)
                .jdbc()
                .sql(
                        "INSERT INTO scheduler.workflows(workflow_id,team_id,name,version,spec,created_by,created_at,updated_at) VALUES (:id,:team,:name,1,CAST(:spec AS jsonb),'user:test',:now,:now)")
                .param("id", workflow)
                .param("team", f.team)
                .param("name", workflow.toString())
                .param("spec", body)
                .param("now", f.now())
                .update();
        f.cell(run)
                .jdbc()
                .sql(
                        "INSERT INTO scheduler.workflow_versions(workflow_id,version,spec,created_at) VALUES (:id,1,CAST(:spec AS jsonb),:now)")
                .param("id", workflow)
                .param("spec", body)
                .param("now", f.now())
                .update();
        List<WorkflowRunCreator.Task> tasks = new ArrayList<>();
        spec.path("tasks")
                .properties()
                .forEach(e -> tasks.add(new WorkflowRunCreator.Task(
                        target(e.getKey()), e.getValue().path("dependsOn").size())));
        f.cell(run)
                .transactions()
                .execute(jdbc -> new WorkflowRunCreator(factory, creator)
                        .create(
                                f.cell(run),
                                new TeamId(f.team),
                                workflow,
                                1,
                                run,
                                f.clock.instant(),
                                Trigger.IMMEDIATE,
                                Duration.ZERO,
                                tasks));
    }

    private RunTarget target(String key) {
        byte[] inputs = WorkflowInputs.load(
                f.cell(run).jdbc(),
                new TeamId(f.team),
                run,
                spec.path("tasks").path(key).path("request"),
                json);
        return new RunTarget(
                new TeamId(f.team),
                TargetType.WORKFLOW_TASK,
                workflow,
                1,
                run,
                TaskKey.of(key),
                new EndpointId(f.endpoint),
                1,
                Priority.of(5),
                null,
                new CallSpec(
                        30000,
                        RequestSnapshot.newBuilder()
                                .setSealedInputs(ByteString.copyFrom(inputs))
                                .build()));
    }

    private UUID task(String key) {
        return f.cell(run)
                .jdbc()
                .sql("SELECT run_id FROM scheduler.run_inflight WHERE target_id=:id AND task_key=:key")
                .param("id", run)
                .param("key", key)
                .query(UUID.class)
                .single();
    }

    private int state(String key) {
        return f.value(task(key), "run_inflight", "state", "run_id");
    }

    private Result claim(String key, int outcome, int error) {
        UUID id = task(key);
        int changed = f.cell(id)
                .jdbc()
                .sql("UPDATE scheduler.run_inflight SET state=3,fence=fence+1 WHERE run_id=:id AND state=2")
                .param("id", id)
                .update();
        assertThat(changed).isEqualTo(1);
        return f.result(id, outcome, error, 0);
    }

    private Result success(String key) {
        return claim(key, 1, 0);
    }

    private Result withOutputs(Result result) {
        byte[] encrypted = cipher.encryptOutput(keys, new RunId(run), "secret", json.writeValueAsBytes(sensitive));
        return result.toBuilder()
                .putOutputs("id", Value.newBuilder().setNumberValue(17).build())
                .setSealedOutputs(ByteString.copyFrom(json.writeValueAsBytes(Map.of(
                        "v", 1, "values", Map.of("secret", Base64.getEncoder().encodeToString(encrypted))))))
                .build();
    }

    private void settle(Result result) {
        f.settler(progression).settle(List.of(result));
    }

    private int counter(String name) {
        return f.value(run, "workflow_runs", name, "workflow_run_id");
    }

    private Dispatch dispatch(String key) throws Exception {
        byte[] payload = f.cell(run)
                .jdbc()
                .sql("SELECT payload FROM scheduler.outbox WHERE run_id=:id AND topic IN (1,2,3)")
                .param("id", task(key))
                .query(byte[].class)
                .single();
        return Dispatch.parseFrom(payload);
    }

    private void reconcile() {
        f.cell(run).transactions().execute(jdbc -> {
            progression.reconcile(f.cell(run), run);
            return null;
        });
    }

    @Test
    void diamondReleasesParallelBranchesThenJoinAndPassesTypedEncryptedInputs() throws Exception {
        diamond("BLOCK_FOR_REVIEW");
        assertThat(state("A")).isEqualTo(2);
        assertThat(state("D")).isEqualTo(1);
        var a = withOutputs(success("A"));
        settle(a);
        settle(a);
        assertThat(counter("succeeded")).isEqualTo(1);
        assertThat(state("B")).isEqualTo(2);
        assertThat(state("C")).isEqualTo(2);
        assertThat(dispatch("B").getFence()).isEqualTo(1);
        settle(success("B"));
        assertThat(state("D")).isEqualTo(1);
        assertThat(f.value(task("D"), "run_inflight", "remaining_deps", "run_id"))
                .isEqualTo(1);
        settle(success("C"));
        assertThat(state("D")).isEqualTo(2);
        Dispatch d = dispatch("D");
        JsonNode inputs = json.readTree(d.getRequest().getSealedInputs().toByteArray());
        assertThat(inputs.path("publicTasks").path("A").path("id").doubleValue())
                .isEqualTo(17);
        byte[] encrypted = Base64.getDecoder()
                .decode(inputs.path("tasks").path("A").path("secret").asString());
        assertThat(json.readTree(cipher.decryptOutput(keys, new RunId(run), "secret", encrypted))
                        .asString())
                .isEqualTo(sensitive);
        assertThat(d.toString()).doesNotContain(sensitive, "Bearer");
        var decrypted = json.readTree(cipher.decryptOutput(keys, new RunId(run), "secret", encrypted))
                .asString();
        var context = new dev.kairos.domain.template.TemplateEngine.Context(
                ProtoIds.toUuid(d.getRunId()),
                Map.of(
                        TaskKey.of("A"),
                        Map.of(
                                "secret",
                                new dev.kairos.domain.template.OutputValue(
                                        dev.kairos.domain.template.JsonValue.of(decrypted), true))));
        var header = dev.kairos.domain.template.TemplateEngine.renderText(
                dev.kairos.domain.template.Template.compile(spec.path("tasks")
                        .path("D")
                        .path("request")
                        .path("headers")
                        .path("Authorization")
                        .asString()),
                dev.kairos.domain.template.TemplateEngine.Destination.HEADER,
                context);
        assertThat(header.value()).isEqualTo("Bearer " + sensitive);
        settle(success("D"));
        assertThat(counter("status")).isEqualTo(3);
        assertThat(counter("succeeded")).isEqualTo(4);
        assertThat(f.cell(run)
                        .jdbc()
                        .sql(
                                "SELECT started_at IS NOT NULL AND finished_at IS NOT NULL FROM scheduler.workflow_runs WHERE workflow_run_id=:id")
                        .param("id", run)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void retryingFailureDoesNotCountOrReleaseChildren() {
        diamond("BLOCK_FOR_REVIEW");
        var result = claim("A", 2, 7);
        settle(result);
        settle(result);
        assertThat(counter("failed")).isZero();
        assertThat(counter("status")).isEqualTo(1);
        assertThat(state("B")).isEqualTo(1);
        assertThat(f.value(task("A"), "run_inflight", "attempt", "run_id")).isEqualTo(2);
    }

    @Test
    void defaultFailureBlocksDependentButIndependentSuccessCanFinish() {
        diamond("BLOCK_FOR_REVIEW");
        settle(withOutputs(success("A")));
        settle(claim("B", 2, 11));
        assertThat(counter("status")).isEqualTo(2);
        assertThat(counter("failed")).isEqualTo(1);
        assertThat(state("D")).isEqualTo(1);
        settle(success("C"));
        reconcile();
        assertThat(counter("status")).isEqualTo(2);
        assertThat(state("D")).isEqualTo(1);
        assertThat(f.cell(run)
                        .jdbc()
                        .sql("SELECT blocked_until=:deadline FROM scheduler.workflow_runs WHERE workflow_run_id=:id")
                        .param("id", run)
                        .param("deadline", f.now().plusSeconds(60))
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void failFastMarksOnlyDescendantsAndFinishesAfterIndependentBranches() {
        diamond("FAIL_FAST");
        settle(withOutputs(success("A")));
        UUID d = task("D");
        settle(claim("B", 2, 11));
        assertThat(f.count(d, "run_inflight")).isZero();
        assertThat(f.value(d, "run_history", "outcome", "run_id")).isEqualTo(6);
        assertThat(counter("failed")).isEqualTo(2);
        assertThat(counter("status")).isEqualTo(1);
        settle(success("C"));
        assertThat(counter("status")).isEqualTo(4);
        assertThat(counter("succeeded")).isEqualTo(2);
    }

    @Test
    void reviewExpiryRemainsDurableWhileAnIndependentTaskFinishes() {
        diamond("BLOCK_FOR_REVIEW");
        settle(withOutputs(success("A")));
        settle(claim("B", 2, 11));
        f.clock.advance(Duration.ofSeconds(60));
        reconcile();
        assertThat(counter("status")).isEqualTo(1);
        assertThat(counter("failed")).isEqualTo(2);
        reconcile();
        assertThat(counter("status")).isEqualTo(1);
        settle(success("C"));
        assertThat(counter("status")).isEqualTo(4);
    }

    @Test
    void watcherExpiryMarkerPreventsReblockingWithoutAnotherResult() {
        diamond("BLOCK_FOR_REVIEW");
        settle(withOutputs(success("A")));
        settle(claim("B", 2, 11));
        f.cell(run)
                .jdbc()
                .sql(
                        "UPDATE scheduler.workflow_runs SET status=1,blocked_until=NULL,review_expired=true WHERE workflow_run_id=:id")
                .param("id", run)
                .update();
        reconcile();
        assertThat(counter("status")).isEqualTo(1);
        assertThat(counter("failed")).isEqualTo(2);
        settle(success("C"));
        assertThat(counter("status")).isEqualTo(4);
    }

    @Test
    void operatorRetryUsesNewTaskIdsAndRetainedOutputsThenConverges() throws Exception {
        diamond("FAIL_FAST");
        settle(withOutputs(success("A")));
        UUID oldB = task("B"), oldD = task("D");
        var failed = claim("B", 2, 11);
        settle(failed);
        settle(success("C"));
        f.cell(run).transactions().execute(jdbc -> {
            creator.create(
                    List.of(
                            factory.workflowRetry(target("B"), f.clock.instant(), 2, 0),
                            factory.workflowRetry(target("D"), f.clock.instant(), 2, 1)),
                    null);
            jdbc.sql(
                            "UPDATE scheduler.workflow_runs SET status=1,failed=0,skipped=0,review_expired=false,finished_at=NULL WHERE workflow_run_id=:id")
                    .param("id", run)
                    .update();
            return null;
        });
        assertThat(task("B")).isNotEqualTo(oldB);
        assertThat(task("D")).isNotEqualTo(oldD);
        settle(failed);
        reconcile();
        assertThat(counter("succeeded")).isEqualTo(2);
        assertThat(counter("failed")).isZero();
        settle(success("B"));
        assertThat(state("D")).isEqualTo(2);
        assertThat(dispatch("D").getRequest().getSealedInputs().toStringUtf8()).contains("publicTasks");
        settle(success("D"));
        assertThat(counter("status")).isEqualTo(3);
        assertThat(counter("succeeded")).isEqualTo(4);
    }

    @Test
    void concurrentJoinResultsQueueExactlyOneChild() throws Exception {
        diamond("BLOCK_FOR_REVIEW");
        settle(withOutputs(success("A")));
        var b = success("B");
        var c = success("C");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> settle(b));
            var second = executor.submit(() -> settle(c));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertThat(state("D")).isEqualTo(2);
        dispatch("D");
        assertThat(counter("succeeded")).isEqualTo(3);
    }

    @Test
    void invalidCiphertextRollsBackHistoryDeletionAndOutputStorage() {
        diamond("BLOCK_FOR_REVIEW");
        var valid = withOutputs(success("A"));
        var bad = valid.toBuilder()
                .setSealedOutputs(ByteString.copyFromUtf8("{\"v\":1,\"values\":{\"secret\":\"AAAA\"}}"))
                .build();
        UUID id = ProtoIds.toUuid(valid.getRunId());
        assertThatThrownBy(() -> settle(bad)).isInstanceOf(RuntimeException.class);
        assertThat(f.count(id, "run_history")).isZero();
        assertThat(f.count(id, "run_inflight")).isEqualTo(1);
        assertThat(state("B")).isEqualTo(1);
        settle(valid);
        assertThat(state("B")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"public-secret", "duplicate", "unknown", "missing-required", "wrong-run"})
    void rejectsInvalidOutputContractsAtomically(String invalid) {
        create("{\"tasks\":{\"A\":{\"endpoint\":\"target\"}}}");
        Result result = withOutputs(success("A"));
        if (invalid.equals("public-secret"))
            result = result.toBuilder()
                    .putOutputs(
                            "secret",
                            Value.newBuilder().setStringValue(sensitive).build())
                    .build();
        if (invalid.equals("duplicate"))
            result = result.toBuilder()
                    .putOutputs(
                            "secret", Value.newBuilder().setStringValue("value").build())
                    .build();
        if (invalid.equals("unknown"))
            result = result.toBuilder()
                    .putOutputs(
                            "extra", Value.newBuilder().setStringValue("value").build())
                    .build();
        if (invalid.equals("missing-required")) {
            f.endpointSpec("{\"outputs\":{\"required\":{\"required\":true}}}");
            result = f.result(task("A"), 1, 0, 0);
        }
        if (invalid.equals("wrong-run")) {
            byte[] ciphertext = cipher.encryptOutput(
                    keys, f.ids.newRunId(Shard.of(12)), "secret", json.writeValueAsBytes(sensitive));
            result = result.toBuilder()
                    .setSealedOutputs(ByteString.copyFrom(json.writeValueAsBytes(Map.of(
                            "v",
                            1,
                            "values",
                            Map.of("secret", Base64.getEncoder().encodeToString(ciphertext))))))
                    .build();
        }
        Result rejected = result;
        assertThatThrownBy(() -> settle(rejected)).isInstanceOf(RuntimeException.class);
        assertThat(counter("succeeded")).isZero();
    }

    @Test
    void skipAndCancellationPropagateAndCannotLeaveWaitingTasksForever() {
        create("{\"tasks\":{\"A\":{\"endpoint\":\"target\"},\"B\":{\"endpoint\":\"target\",\"dependsOn\":[\"A\"]}}}");
        UUID b = task("B");
        settle(claim("A", 5, 0));
        assertThat(f.value(b, "run_history", "outcome", "run_id")).isEqualTo(6);
        assertThat(counter("status")).isEqualTo(4);
    }

    @Test
    void operatorCancelledWorkflowDoesNotDoubleCountActiveCallsOrReleaseChildren() {
        diamond("BLOCK_FOR_REVIEW");
        var result = success("A");
        f.cell(run)
                .jdbc()
                .sql(
                        "UPDATE scheduler.workflow_runs SET status=5,cancelled=4,finished_at=:now WHERE workflow_run_id=:id")
                .param("id", run)
                .param("now", f.now())
                .update();
        f.cell(run)
                .jdbc()
                .sql("UPDATE scheduler.run_inflight SET cancel_requested=true WHERE target_id=:id")
                .param("id", run)
                .update();
        settle(result);
        assertThat(counter("cancelled")).isEqualTo(4);
        assertThat(counter("status")).isEqualTo(5);
        assertThat(state("B")).isEqualTo(1);
    }

    @Test
    void pinnedVersionsSurviveEndpointAndDefinitionUpdates() throws Exception {
        diamond("BLOCK_FOR_REVIEW");
        f.control
                .sql("UPDATE scheduler.api_endpoints SET version=2,spec='{\"timeoutMs\":1000}' WHERE endpoint_id=:id")
                .param("id", f.endpoint)
                .update();
        f.cell(run)
                .jdbc()
                .sql("UPDATE scheduler.workflows SET version=2,spec='{}' WHERE workflow_id=:id")
                .param("id", workflow)
                .update();
        settle(withOutputs(success("A")));
        assertThat(dispatch("B").getEndpointVersion()).isEqualTo(1);
        assertThat(dispatch("B").getDefinitionVersion()).isEqualTo(1);
        assertThat(dispatch("B").getTimeoutMs()).isEqualTo(30000);
    }

    @Test
    void sharedInputsFilterUnusedOutputsAndKeepNestedJsonNullAndTypes() {
        create("{\"tasks\":{\"A\":{\"endpoint\":\"target\"}}}");
        var object = com.google.protobuf.Struct.newBuilder()
                .putFields("nil", Value.newBuilder().setNullValueValue(0).build())
                .putFields("flag", Value.newBuilder().setBoolValue(true).build())
                .build();
        settle(success("A").toBuilder()
                .putOutputs("id", Value.newBuilder().setStructValue(object).build())
                .build());
        JsonNode request = json.readTree("{\"body\":{\"data\":\"{{ tasks.A.outputs.id }}\"}}");
        JsonNode inputs =
                json.readTree(WorkflowInputs.load(f.cell(run).jdbc(), new TeamId(f.team), run, request, json));
        assertThat(inputs.path("publicTasks").path("A").path("id").path("nil").isNull())
                .isTrue();
        assertThat(inputs.path("publicTasks").path("A").path("id").path("flag").booleanValue())
                .isTrue();
        assertThat(inputs.path("tasks").isEmpty()).isTrue();
    }

    @Test
    void reconcilerFindsRecoveryFailuresWithoutAnotherResult() {
        create("{\"onTaskFailure\":\"FAIL_FAST\",\"tasks\":{\"A\":{\"endpoint\":\"target\"}}}");
        UUID a = task("A");
        f.cell(run)
                .jdbc()
                .sql(
                        "INSERT INTO scheduler.run_history(scheduled_at,run_id,attempt,infra_attempt,team_id,target_type,definition_id,target_id,task_key,endpoint_id,trigger,outcome,final,finished_at) SELECT scheduled_at,run_id,attempt,infra_attempt,team_id,target_type,definition_id,target_id,task_key,endpoint_id,trigger,3,true,:now FROM scheduler.run_inflight WHERE run_id=:id")
                .param("id", a)
                .param("now", f.now())
                .update();
        f.cell(run)
                .jdbc()
                .sql("DELETE FROM scheduler.run_inflight WHERE run_id=:id")
                .param("id", a)
                .update();
        var reconciler = new WorkflowReconciler(f.cells, progression);
        reconciler.poll();
        assertThat(counter("status")).isEqualTo(4);
        reconciler.poll();
        reconciler.scheduled();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"4,5", "5,3"})
    void singleCancelledOrSkippedLeafFinalizesWorkflow(int outcome, int status) {
        create("{\"tasks\":{\"A\":{\"endpoint\":\"target\"}}}");
        settle(claim("A", outcome, 0));
        assertThat(counter("status")).isEqualTo(status);
        assertThat(counter(outcome == 4 ? "cancelled" : "skipped")).isEqualTo(1);
    }

    @Test
    void childOutboxFailureRollsBackHistoryOutputsAndAllTaskChanges() {
        diamond("BLOCK_FOR_REVIEW");
        var result = withOutputs(success("A"));
        UUID a = ProtoIds.toUuid(result.getRunId());
        var rejected = org.mockito.Mockito.mock(OutboxWriter.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("outbox unavailable"))
                .when(rejected)
                .write(org.mockito.ArgumentMatchers.anyList());
        var failing = new JdbcWorkflowProgression(
                f.cells, f.control, rejected, new WorkflowOutputs(f.control, json, keysets, cipher), json, f.clock);
        assertThatThrownBy(() -> f.settler(failing).settle(List.of(result))).isInstanceOf(IllegalStateException.class);
        assertThat(f.count(a, "run_history")).isZero();
        assertThat(f.count(a, "run_inflight")).isEqualTo(1);
        assertThat(state("B")).isEqualTo(1);
        assertThat(counter("succeeded")).isZero();
        assertThat(f.cell(run)
                        .jdbc()
                        .sql("SELECT count(*) FROM scheduler.task_outputs WHERE workflow_run_id=:id")
                        .param("id", run)
                        .query(Long.class)
                        .single())
                .isZero();
        settle(result);
        assertThat(state("B")).isEqualTo(2);
    }

    @Test
    void refusesProgressionOutsideOwningTransaction() {
        diamond("BLOCK_FOR_REVIEW");
        assertThatThrownBy(() -> progression.reconcile(f.cell(run), run)).isInstanceOf(IllegalStateException.class);
    }
}
