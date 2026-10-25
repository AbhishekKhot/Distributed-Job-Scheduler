package dev.kairos.api.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.workflows.application.WorkflowDefinition;
import dev.kairos.api.workflows.application.WorkflowEndpointCatalog;
import dev.kairos.api.workflows.application.WorkflowStore;
import dev.kairos.domain.id.KairosId;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.shard.ShardKey;
import dev.kairos.persistence.cell.CellRouter;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Cancel;
import dev.kairos.proto.v1.Dispatch;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP and PostgreSQL acceptance for workflow APIs 46–58. */
class WorkflowsIntegrationTest extends ApiApplicationTest {
    private static final String ENDPOINT = """
            {"name":"workflow-target","method":"POST","urlTemplate":"https://orders.internal/call","timeoutMs":30000,
            "auth":{"type":"NONE"},"retryPolicy":{"maxRetries":3,"strategy":"FIXED","baseDelaySec":1,"maxDelaySec":10,"maxAgeSec":600},
            "limits":{"maxConcurrency":10,"ratePerSec":20},"outputs":{
                "id":{"from":"BODY","path":"$.id","required":true,"sensitive":false},
                "token":{"from":"BODY","path":"$.token","required":false,"sensitive":true}}}
            """;
    private static final String DIAMOND = """
            {"root":{"endpoint":"workflow-target"},
             "left":{"endpoint":"workflow-target","dependsOn":["root"],"request":{"headers":{"Authorization":"Bearer {{ tasks.root.outputs.token }}"},"body":{"ref":"{{ tasks.root.outputs.id }}"}}},
             "right":{"endpoint":"workflow-target","dependsOn":["root"]},
             "join":{"endpoint":"workflow-target","dependsOn":["left","right"]}}
            """;
    private static final String INTERVAL = "{\"type\":\"INTERVAL\",\"everySec\":60}";
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private CellRouter cells;

    @Autowired
    private WorkflowStore workflows;

    @Autowired
    private WorkflowEndpointCatalog endpoints;

    @Autowired
    private KairosIdFactory ids;

    @Test
    void diamondCreatesOneQueuedRootAndWaitingTasksWithPinnedPlanAndEndpoints() throws Exception {
        Caller caller = caller();
        UUID workflow = create(caller, "diamond", INTERVAL, DIAMOND);
        UUID run = start(caller, workflow);
        JsonNode detail = ok(send("GET", "/v1/workflow-runs/" + run, caller.token(), null), 200);
        assertThat(detail.path("counts").path("total").asInt()).isEqualTo(4);
        assertThat(detail.path("tasks").size()).isEqualTo(4);
        assertThat(db(workflow)
                        .sql("SELECT count(*) FROM scheduler.run_inflight WHERE target_id=:run AND state=2")
                        .param("run", run)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(db(workflow)
                        .sql(
                                "SELECT remaining_deps FROM scheduler.run_inflight WHERE target_id=:run AND task_key='join'")
                        .param("run", run)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertThat(db(workflow)
                        .sql(
                                "SELECT count(*) FROM scheduler.outbox WHERE run_id IN (SELECT run_id FROM scheduler.run_inflight WHERE target_id=:run)")
                        .param("run", run)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        JsonNode stored = json.readTree(db(workflow)
                .sql("SELECT spec::text FROM scheduler.workflow_versions WHERE workflow_id=:id AND version=1")
                .param("id", workflow)
                .query(String.class)
                .single());
        assertThat(stored.path("plan").path("roots").path(0).asString()).isEqualTo("root");
        assertThat(stored.path("plan")
                        .path("remainingDependencies")
                        .path("join")
                        .asInt())
                .isEqualTo(2);
        JsonNode pins = json.readTree(db(workflow)
                .sql("SELECT task_pins::text FROM scheduler.workflow_runs WHERE workflow_run_id=:id")
                .param("id", run)
                .query(String.class)
                .single());
        assertThat(pins.size()).isEqualTo(4);
        assertThat(pins.path("root").path("version").asInt()).isEqualTo(1);
        Dispatch dispatch = Dispatch.parseFrom(db(workflow)
                .sql(
                        "SELECT payload FROM scheduler.outbox WHERE run_id IN (SELECT run_id FROM scheduler.run_inflight WHERE target_id=:run)")
                .param("run", run)
                .query(byte[].class)
                .single());
        assertThat(dispatch.getTaskKey()).isEqualTo("root");
        assertThat(dispatch.getDefinitionVersion()).isEqualTo(1);
        assertThat(KairosId.shardOf(run)).isEqualTo(KairosId.shardOf(workflow));
    }

    @Test
    void cyclicGraphReturnsCyclePathAndInvalidReferencesAreRejected() {
        Caller caller = caller();
        String cycle =
                "{\"a\":{\"endpoint\":\"workflow-target\",\"dependsOn\":[\"b\"]},\"b\":{\"endpoint\":\"workflow-target\",\"dependsOn\":[\"a\"]}}";
        JsonNode error = problem(
                send("POST", "/v1/workflows", caller.token(), body("cycle", INTERVAL, cycle)), 400, "cycle-detected");
        assertThat(error.path("detail").asString()).contains("a -> b -> a");
        problem(
                send(
                        "POST",
                        "/v1/workflows",
                        caller.token(),
                        body(
                                "invalid-reference",
                                INTERVAL,
                                DIAMOND.replace("tasks.root.outputs.id", "tasks.join.outputs.id"))),
                400,
                "invalid-placeholder");
        problem(
                send(
                        "POST",
                        "/v1/workflows",
                        caller.token(),
                        body(
                                "invalid-output",
                                INTERVAL,
                                DIAMOND.replace("tasks.root.outputs.id", "tasks.root.outputs.unknown"))),
                400,
                "invalid-placeholder");
        problem(
                send(
                        "POST",
                        "/v1/workflows",
                        caller.token(),
                        body(
                                "sensitive-query",
                                INTERVAL,
                                DIAMOND.replace("\"headers\":{\"Authorization\"", "\"query\":{\"token\""))),
                400,
                "invalid-placeholder");
        problem(
                send(
                        "POST",
                        "/v1/workflows",
                        caller.token(),
                        body("missing-endpoint", INTERVAL, DIAMOND.replace("workflow-target", "other-team-endpoint"))),
                400,
                "endpoint-not-found");
    }

    @Test
    void duplicateTaskKeysAreRejectedBeforeTheMapIsBuilt() {
        Caller caller = caller();
        String duplicate = "{\"root\":{\"endpoint\":\"workflow-target\"},\"root\":{\"endpoint\":\"workflow-target\"}}";
        problem(
                send("POST", "/v1/workflows", caller.token(), body("duplicate", INTERVAL, duplicate)),
                400,
                "validation");
    }

    @Test
    void taskQuotaAndDefinitionSizeAreEnforced() {
        Caller caller = caller();
        ApiTestFixtures.control()
                .sql("UPDATE scheduler.teams SET quotas=CAST(:quotas AS jsonb) WHERE team_id=:team")
                .param("quotas", "{\"maxWorkflowTasks\":1}")
                .param("team", caller.team().value())
                .update();
        problem(send("POST", "/v1/workflows", caller.token(), body("quota", INTERVAL, DIAMOND)), 429, "quota-exceeded");
        String large = body(
                "large",
                INTERVAL,
                "{\"root\":{\"endpoint\":\"workflow-target\",\"request\":{\"body\":\""
                        + "x".repeat(WorkflowDefinition.MAX_BYTES) + "\"}}}");
        problem(send("POST", "/v1/workflows", caller.token(), large), 400, "validation");
        String many = "{"
                + String.join(
                        ",",
                        java.util.stream.IntStream.range(0, 201)
                                .mapToObj(i -> "\"t" + i + "\":{\"endpoint\":\"workflow-target\"}")
                                .toList())
                + "}";
        problem(send("POST", "/v1/workflows", caller.token(), body("too-many", INTERVAL, many)), 400, "too-many-tasks");
    }

    @Test
    void createAndManualRunIdempotencySurviveConcurrentRequests() throws Exception {
        Caller caller = caller();
        String key = UUID.randomUUID().toString();
        String request = body("idempotent", INTERVAL, DIAMOND);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 4; i++)
                futures.add(CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                start.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                            return send("POST", "/v1/workflows", caller.token(), request, "Idempotency-Key", key);
                        },
                        pool));
            start.countDown();
            var responses = futures.stream().map(CompletableFuture::join).toList();
            responses.forEach(response -> ok(response, 201));
            assertThat(responses.stream().map(HttpResponse::body).distinct()).hasSize(1);
            UUID workflow = id(json.readTree(responses.getFirst().body()));
            String runKey = UUID.randomUUID().toString();
            var first = send(
                    "POST", "/v1/workflows/" + workflow + "/runnow", caller.token(), null, "Idempotency-Key", runKey);
            ok(first, 202);
            assertThat(send(
                                    "POST",
                                    "/v1/workflows/" + workflow + "/runnow",
                                    caller.token(),
                                    null,
                                    "Idempotency-Key",
                                    runKey)
                            .body())
                    .isEqualTo(first.body());
            assertThat(count(workflow, "workflow_runs", "workflow_id")).isEqualTo(1);
        }
        problem(
                send(
                        "POST",
                        "/v1/workflows",
                        caller.token(),
                        request.replace("\"priority\":5", "\"priority\":6"),
                        "Idempotency-Key",
                        key),
                409,
                "idempotency-key-reused");
        problem(send("POST", "/v1/workflows", caller.token(), request), 409, "workflow-name-taken");
    }

    @Test
    void listFansOutWithTeamBoundCursorsAndTeamIsolation() {
        Caller caller = caller();
        UUID a = create(caller, nameInCell(caller, 0), INTERVAL, DIAMOND);
        UUID b = create(caller, nameInCell(caller, 1), INTERVAL, DIAMOND);
        JsonNode first = ok(send("GET", "/v1/workflows?status=ACTIVE&limit=1", caller.token(), null), 200);
        String cursor = first.path("nextCursor").asString();
        JsonNode next = ok(send("GET", "/v1/workflows?limit=1&cursor=" + cursor, caller.token(), null), 200);
        assertThat(first.path("items").path(0).path("id").asString())
                .isNotEqualTo(next.path("items").path(0).path("id").asString());
        problem(send("GET", "/v1/workflows?status=PAUSED&cursor=" + cursor, caller.token(), null), 400, "validation");
        Caller other = caller();
        problem(send("GET", "/v1/workflows?cursor=" + cursor, other.token(), null), 400, "validation");
        for (UUID workflow : List.of(a, b)) {
            problem(send("GET", "/v1/workflows/" + workflow, other.token(), null), 404, "not-found");
            problem(send("POST", "/v1/workflows/" + workflow + "/runnow", other.token(), null), 404, "not-found");
            problem(send("DELETE", "/v1/workflows/" + workflow, other.token(), null), 404, "not-found");
        }
        UUID run = start(caller, a);
        problem(send("GET", "/v1/workflow-runs/" + run, other.token(), null), 404, "not-found");
        problem(send("POST", "/v1/workflow-runs/" + run + "/cancel", other.token(), null), 404, "not-found");
        problem(
                send("POST", "/v1/workflow-runs/" + run + "/retry", other.token(), "{\"from\":\"FAILED_TASKS\"}"),
                404,
                "not-found");
    }

    @Test
    void updateRequiresEtagStoresNewPlanAndPreservesQueuedDefinitionVersion() {
        Caller caller = caller();
        UUID workflow = create(caller, "versioned", INTERVAL, DIAMOND);
        UUID run = start(caller, workflow);
        String request = body("versioned", "{\"type\":\"INTERVAL\",\"everySec\":120}", DIAMOND);
        problem(send("PUT", "/v1/workflows/" + workflow, caller.token(), request), 428, "if-match-required");
        var updated = send("PUT", "/v1/workflows/" + workflow, caller.token(), request, "If-Match", "\"1\"");
        ok(updated, 200);
        assertThat(updated.headers().firstValue("ETag")).contains("\"2\"");
        problem(
                send("PUT", "/v1/workflows/" + workflow, caller.token(), request, "If-Match", "\"1\""),
                412,
                "version-mismatch");
        problem(
                send(
                        "PUT",
                        "/v1/workflows/" + workflow,
                        caller.token(),
                        request.replace("versioned", "renamed"),
                        "If-Match",
                        "\"2\""),
                400,
                "name-immutable");
        assertThat(count(workflow, "workflow_versions", "workflow_id")).isEqualTo(2);
        assertThat(db(workflow)
                        .sql("SELECT DISTINCT definition_version FROM scheduler.run_inflight WHERE target_id=:id")
                        .param("id", run)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(db(workflow)
                        .sql("SELECT definition_version FROM scheduler.schedules WHERE schedule_id=:id")
                        .param("id", workflow)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void pauseAndResumeKeepOpenRunsAndMissedScheduleCursor() {
        Caller caller = caller();
        UUID workflow = create(caller, "pause", INTERVAL, DIAMOND);
        UUID run = start(caller, workflow);
        Instant missed = Instant.now().minusSeconds(180).truncatedTo(ChronoUnit.MICROS);
        db(workflow)
                .sql("UPDATE scheduler.schedules SET next_fire_at=:time WHERE schedule_id=:id")
                .param("time", ApiTestFixtures.utc(missed))
                .param("id", workflow)
                .update();
        ok(send("POST", "/v1/workflows/" + workflow + "/pause", caller.token(), null), 200);
        problem(send("POST", "/v1/workflows/" + workflow + "/pause", caller.token(), null), 409, "invalid-state");
        assertThat(count(workflow, "run_inflight", "target_id", run)).isEqualTo(4);
        JsonNode resumed = ok(send("POST", "/v1/workflows/" + workflow + "/resume", caller.token(), null), 200);
        assertThat(Instant.parse(resumed.path("nextFireAt").asString())).isEqualTo(missed);
        problem(send("POST", "/v1/workflows/" + workflow + "/resume", caller.token(), null), 409, "invalid-state");
    }

    @Test
    void cancellingWorkflowRunCancelsWaitingTasksAndSendsOneFencedRunningCancel() throws Exception {
        Caller caller = caller();
        UUID workflow = create(caller, "cancel-run", INTERVAL, DIAMOND);
        UUID run = start(caller, workflow);
        UUID root = taskRun(workflow, run, "root");
        db(workflow)
                .sql("UPDATE scheduler.run_inflight SET state=3,fence=11 WHERE run_id=:id")
                .param("id", root)
                .update();
        JsonNode result = ok(send("POST", "/v1/workflow-runs/" + run + "/cancel", caller.token(), null), 200);
        assertThat(result.path("counts").path("cancelled").asInt()).isEqualTo(4);
        assertThat(count(workflow, "run_inflight", "target_id", run)).isEqualTo(1);
        assertThat(count(workflow, "run_history", "target_id", run)).isEqualTo(3);
        Cancel cancel = Cancel.parseFrom(db(workflow)
                .sql("SELECT payload FROM scheduler.outbox WHERE run_id=:id AND topic=4")
                .param("id", root)
                .query(byte[].class)
                .single());
        assertThat(ProtoIds.toUuid(cancel.getRunId())).isEqualTo(root);
        assertThat(cancel.getFence()).isEqualTo(11);
        problem(send("POST", "/v1/workflow-runs/" + run + "/cancel", caller.token(), null), 409, "run-final");
    }

    @Test
    void cancelAndDeleteDefinitionsCancelOpenRunsAndDeletedNamesAreReusable() {
        Caller caller = caller();
        UUID workflow = create(caller, "delete", INTERVAL, DIAMOND);
        UUID run = start(caller, workflow);
        ok(send("POST", "/v1/workflows/" + workflow + "/cancel", caller.token(), null), 200);
        assertThat(ok(send("GET", "/v1/workflow-runs/" + run, caller.token(), null), 200)
                        .path("status")
                        .asString())
                .isEqualTo("CANCELLED");
        problem(send("POST", "/v1/workflows/" + workflow + "/cancel", caller.token(), null), 409, "invalid-state");
        problem(send("POST", "/v1/workflows/" + workflow + "/runnow", caller.token(), null), 409, "invalid-state");
        assertThat(send("DELETE", "/v1/workflows/" + workflow, caller.token(), null)
                        .statusCode())
                .isEqualTo(204);
        problem(send("DELETE", "/v1/workflows/" + workflow, caller.token(), null), 404, "not-found");
        assertThat(create(caller, "delete", INTERVAL, DIAMOND)).isNotEqualTo(workflow);
    }

    @Test
    void runListUsesBoundFiltersCursorAndCounts() {
        Caller caller = caller();
        UUID workflow = create(caller, "runs", INTERVAL, DIAMOND);
        start(caller, workflow);
        start(caller, workflow);
        JsonNode first = ok(
                send("GET", "/v1/workflows/" + workflow + "/runs?status=RUNNING&limit=1", caller.token(), null), 200);
        assertThat(first.path("items").path(0).path("counts").path("total").asInt())
                .isEqualTo(4);
        String cursor = first.path("nextCursor").asString();
        JsonNode second = ok(
                send("GET", "/v1/workflows/" + workflow + "/runs?limit=1&cursor=" + cursor, caller.token(), null), 200);
        assertThat(first.path("items").path(0).path("workflowRunId").asString())
                .isNotEqualTo(second.path("items").path(0).path("workflowRunId").asString());
        problem(
                send("GET", "/v1/workflows/" + workflow + "/runs?status=FAILED&cursor=" + cursor, caller.token(), null),
                400,
                "validation");
    }

    @Test
    void retryRequeuesOnlyFailedAndSkippedTasksWithNewIdsAndRetainsSuccessfulOutputs() throws Exception {
        Caller caller = caller();
        UUID workflow = create(caller, "retry", INTERVAL, DIAMOND);
        UUID run = start(caller, workflow);
        UUID left = taskRun(workflow, run, "left");
        archive(workflow, run, "root", 1);
        archive(workflow, run, "left", 3);
        archive(workflow, run, "right", 1);
        archive(workflow, run, "join", 6);
        db(workflow)
                .sql(
                        "UPDATE scheduler.workflow_runs SET status=4,succeeded=2,failed=1,skipped=1,finished_at=now() WHERE workflow_run_id=:id")
                .param("id", run)
                .update();
        db(workflow)
                .sql(
                        "INSERT INTO scheduler.task_outputs(scheduled_at,workflow_run_id,task_key,team_id,outputs,sealed,created_at) SELECT scheduled_at,workflow_run_id,'root',team_id,'{\"id\":42}'::jsonb,:sealed,now() FROM scheduler.workflow_runs WHERE workflow_run_id=:id")
                .param("id", run)
                .param(
                        "sealed",
                        "{\"v\":1,\"values\":{\"token\":\"Y2lwaGVy\"}}"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .update();
        JsonNode detail = ok(send("GET", "/v1/workflow-runs/" + run, caller.token(), null), 200);
        assertThat(detail.toString()).contains("[REDACTED]").doesNotContain("Y2lwaGVy");
        JsonNode retried = ok(
                send("POST", "/v1/workflow-runs/" + run + "/retry", caller.token(), "{\"from\":\"FAILED_TASKS\"}"),
                202);
        assertThat(retried.path("requeuedTasks").size()).isEqualTo(2);
        assertThat(taskRun(workflow, run, "left")).isNotEqualTo(left);
        assertThat(db(workflow)
                        .sql("SELECT state FROM scheduler.run_inflight WHERE target_id=:id AND task_key='left'")
                        .param("id", run)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertThat(db(workflow)
                        .sql(
                                "SELECT remaining_deps FROM scheduler.run_inflight WHERE target_id=:id AND task_key='join'")
                        .param("id", run)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(db(workflow)
                        .sql("SELECT DISTINCT series_start FROM scheduler.run_inflight WHERE target_id=:id")
                        .param("id", run)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertThat(count(workflow, "task_outputs", "workflow_run_id", run)).isEqualTo(1);
        Dispatch dispatch = Dispatch.parseFrom(db(workflow)
                .sql("SELECT payload FROM scheduler.outbox WHERE run_id=:id")
                .param("id", taskRun(workflow, run, "left"))
                .query(byte[].class)
                .single());
        JsonNode inputs = json.readTree(dispatch.getRequest().getSealedInputs().toByteArray());
        assertThat(inputs.path("publicTasks").path("root").path("id").asInt()).isEqualTo(42);
        assertThat(inputs.path("tasks").path("root").path("token").asString()).isEqualTo("Y2lwaGVy");
        problem(
                send("POST", "/v1/workflow-runs/" + run + "/retry", caller.token(), "{\"from\":\"FAILED_TASKS\"}"),
                409,
                "invalid-state");
    }

    @Test
    void immediateWorkflowCreationAndForcedRollbackAreAtomic() {
        Caller caller = caller();
        UUID immediate = create(caller, "immediate", "{\"type\":\"IMMEDIATE\"}", DIAMOND);
        assertThat(count(immediate, "workflow_runs", "workflow_id")).isEqualTo(1);
        assertThat(count(immediate, "schedules", "schedule_id")).isZero();
        Instant now = Instant.now();
        var definition = WorkflowDefinition.parse(
                json.readTree(body("rollback", "{\"type\":\"IMMEDIATE\"}", DIAMOND)),
                now,
                endpoints,
                caller.team(),
                200,
                json);
        UUID id = ids.newWorkflowId(ShardKey.of(caller.team().value(), "rollback"))
                .value();
        assertThatThrownBy(() -> cells.forId(id).transactions().execute(jdbc -> {
                    workflows.create(caller.team(), id, "user:" + caller.user().value(), definition, now);
                    throw new IllegalStateException("force rollback");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count(id, "workflows", "workflow_id")).isZero();
        assertThat(count(id, "workflow_versions", "workflow_id")).isZero();
        assertThat(count(id, "workflow_runs", "workflow_id")).isZero();
        assertThat(db(id).sql("SELECT count(*) FROM scheduler.run_inflight WHERE definition_id=:id")
                        .param("id", id)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void largeWorkflowTemplatesStayInThePinnedDefinitionInsteadOfTheOutbox() {
        Caller caller = caller();
        String value = "[" + String.join(",", java.util.Collections.nCopies(25, "\"" + "x".repeat(12000) + "\"")) + "]";
        String tasks = "{\"root\":{\"endpoint\":\"workflow-target\",\"request\":{\"body\":" + value + "}}}";
        UUID workflow = create(caller, "large-pinned-body", "{\"type\":\"IMMEDIATE\"}", tasks);
        assertThat(db(workflow)
                        .sql(
                                "SELECT max(octet_length(payload)) FROM scheduler.outbox WHERE run_id IN (SELECT run_id FROM scheduler.run_inflight WHERE definition_id=:id)")
                        .param("id", workflow)
                        .query(Integer.class)
                        .single())
                .isLessThan(1000);
    }

    @Test
    void retryingBlockedRunKeepsUnaffectedWaitingTasksAndUsesOriginalEndpointPins() {
        Caller caller = caller();
        UUID workflow = create(caller, "blocked-retry", INTERVAL, DIAMOND);
        UUID run = start(caller, workflow);
        archive(workflow, run, "root", 3);
        db(workflow)
                .sql(
                        "UPDATE scheduler.workflow_runs SET status=2,failed=1,blocked_until=now()+interval '1 day' WHERE workflow_run_id=:id")
                .param("id", run)
                .update();
        UUID endpoint =
                endpoints.find(caller.team(), "workflow-target").orElseThrow().id();
        ok(
                send(
                        "PUT",
                        "/v1/endpoints/" + endpoint,
                        caller.token(),
                        ENDPOINT.replace("30000", "45000"),
                        "If-Match",
                        "\"1\""),
                200);
        JsonNode retried = ok(
                send("POST", "/v1/workflow-runs/" + run + "/retry", caller.token(), "{\"from\":\"FAILED_TASKS\"}"),
                202);
        assertThat(retried.path("requeuedTasks").size()).isEqualTo(1);
        assertThat(db(workflow)
                        .sql("SELECT count(*) FROM scheduler.run_inflight WHERE target_id=:id AND state=1")
                        .param("id", run)
                        .query(Integer.class)
                        .single())
                .isEqualTo(3);
        assertThat(db(workflow)
                        .sql(
                                "SELECT endpoint_version FROM scheduler.run_inflight WHERE target_id=:id AND task_key='root'")
                        .param("id", run)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(db(workflow)
                        .sql("SELECT blocked_until IS NULL FROM scheduler.workflow_runs WHERE workflow_run_id=:id")
                        .param("id", run)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    private Caller caller() {
        Caller caller = newUser(ApiTestFixtures.insertTeam());
        ok(send("POST", "/v1/endpoints", caller.token(), ENDPOINT), 201);
        return caller;
    }

    private UUID create(Caller caller, String name, String schedule, String tasks) {
        return id(ok(send("POST", "/v1/workflows", caller.token(), body(name, schedule, tasks)), 201));
    }

    private UUID start(Caller caller, UUID workflow) {
        return UUID.fromString(ok(send("POST", "/v1/workflows/" + workflow + "/runnow", caller.token(), null), 202)
                .path("workflowRunId")
                .asString());
    }

    private static String body(String name, String schedule, String tasks) {
        return "{\"name\":\"" + name + "\",\"schedule\":" + schedule + ",\"priority\":5,\"tasks\":" + tasks + "}";
    }

    private JsonNode ok(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }

    private JsonNode problem(HttpResponse<String> response, int status, String code) {
        JsonNode result = ok(response, status);
        assertThat(result.path("type").asString()).isEqualTo("/errors/" + code);
        return result;
    }

    private static UUID id(JsonNode node) {
        return UUID.fromString(node.path("id").asString());
    }

    private JdbcClient db(UUID id) {
        return cells.forId(id).jdbc();
    }

    private long count(UUID workflow, String table, String column) {
        return count(workflow, table, column, workflow);
    }

    private long count(UUID workflow, String table, String column, UUID value) {
        return db(workflow)
                .sql("SELECT count(*) FROM scheduler." + table + " WHERE " + column + "=:id")
                .param("id", value)
                .query(Long.class)
                .single();
    }

    private UUID taskRun(UUID workflow, UUID run, String task) {
        return db(workflow)
                .sql("SELECT run_id FROM scheduler.run_inflight WHERE target_id=:id AND task_key=:task")
                .param("id", run)
                .param("task", task)
                .query(UUID.class)
                .single();
    }

    private void archive(UUID workflow, UUID run, String task, int outcome) {
        UUID taskRun = taskRun(workflow, run, task);
        db(workflow)
                .sql(
                        "INSERT INTO scheduler.run_history(scheduled_at,run_id,attempt,infra_attempt,team_id,target_type,definition_id,target_id,task_key,endpoint_id,trigger,outcome,final,finished_at) SELECT scheduled_at,run_id,attempt,infra_attempt,team_id,target_type,definition_id,target_id,task_key,endpoint_id,trigger,:outcome,true,now() FROM scheduler.run_inflight WHERE run_id=:id")
                .param("outcome", outcome)
                .param("id", taskRun)
                .update();
        db(workflow)
                .sql("DELETE FROM scheduler.outbox WHERE run_id=:id")
                .param("id", taskRun)
                .update();
        db(workflow)
                .sql("DELETE FROM scheduler.run_inflight WHERE run_id=:id")
                .param("id", taskRun)
                .update();
    }

    private static String nameInCell(Caller caller, int cell) {
        for (int i = 0; ; i++) {
            String name = "workflow-cell-" + cell + "-" + i;
            if (ShardKey.of(caller.team().value(), name).value() / 128 == cell) return name;
        }
    }
}
