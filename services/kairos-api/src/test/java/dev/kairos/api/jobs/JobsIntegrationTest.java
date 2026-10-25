package dev.kairos.api.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kairos.api.ApiApplicationTest;
import dev.kairos.api.ApiTestFixtures;
import dev.kairos.api.jobs.application.JobCallBuilder;
import dev.kairos.api.jobs.application.JobEndpointCatalog;
import dev.kairos.api.jobs.application.JobSchedule;
import dev.kairos.api.jobs.application.JobSpec;
import dev.kairos.api.jobs.application.JobStore;
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

/** Real HTTP/SQL acceptance for LLD job operations 34–45, including concurrent callers and rollback. */
class JobsIntegrationTest extends ApiApplicationTest {
    private static final String ENDPOINT = """
            {"name":"job-target","method":"POST","urlTemplate":"https://orders.internal/v1/{orderId}",
             "defaultHeaders":{"X-Default":"present"},"auth":{"type":"NONE"},"timeoutMs":30000,
             "retryPolicy":{"maxRetries":3,"strategy":"FIXED","baseDelaySec":1,"maxDelaySec":10,"maxAgeSec":600},
             "limits":{"maxConcurrency":10,"ratePerSec":20}}
            """;
    private static final String INTERVAL = "{\"type\":\"INTERVAL\",\"everySec\":60}";
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private CellRouter cells;

    @Autowired
    private JobStore jobs;

    @Autowired
    private dev.kairos.persistence.jdbc.DataSourceRegistry databases;

    @Autowired
    private dev.kairos.api.jobs.application.JobCreationGuard creationGuard;

    @Autowired
    private JobEndpointCatalog endpoints;

    @Autowired
    private KairosIdFactory ids;

    @Test
    void createsAllScheduleKindsAndPinsImmediateDispatchInTheOwningCell() throws Exception {
        Caller caller = caller();
        List<String> schedules = List.of(
                INTERVAL,
                "{\"type\":\"CRON\",\"expression\":\"0 * * * *\",\"timezone\":\"UTC\"}",
                "{\"type\":\"ONE_TIME\",\"at\":\"" + Instant.now().plusSeconds(120) + "\"}",
                "{\"type\":\"IMMEDIATE\"}");
        for (int i = 0; i < schedules.size(); i++) {
            String name = "schedule-" + i;
            JsonNode created = create(caller, name, schedules.get(i));
            UUID job = id(created);
            assertThat(KairosId.shardOf(job))
                    .isEqualTo(ShardKey.of(caller.team().value(), name));
            assertThat(created.path("status").asString()).isEqualTo("ACTIVE");
            assertThat(count(job, "job_versions", "job_id")).isEqualTo(1);
            if (i == 3) {
                assertThat(count(job, "schedules", "schedule_id")).isZero();
                assertThat(count(job, "run_inflight", "target_id")).isEqualTo(1);
                Dispatch dispatch = Dispatch.parseFrom(db(job).sql(
                                "SELECT payload FROM scheduler.outbox WHERE run_id IN (SELECT run_id FROM scheduler.run_inflight WHERE target_id=:id)")
                        .param("id", job)
                        .query(byte[].class)
                        .single());
                assertThat(dispatch.getDefinitionVersion()).isEqualTo(1);
                assertThat(dispatch.getEndpointVersion()).isEqualTo(1);
                assertThat(dispatch.getRequest().getPathParamsMap()).containsEntry("orderId", "42");
                assertThat(dispatch.getRequest().getBody().toStringUtf8()).contains("original");
            } else {
                assertThat(created.path("nextFireAt").isString()).isTrue();
                assertThat(count(job, "schedules", "schedule_id")).isEqualTo(1);
                assertThat(count(job, "run_inflight", "target_id")).isZero();
            }
        }
    }

    @Test
    void simultaneousCreatesWithTheSameKeyReplayOneJobAndOneOutbox() throws Exception {
        Caller caller = caller();
        String request = body("concurrent", "{\"type\":\"IMMEDIATE\"}");
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++)
                futures.add(CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                start.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                            return send("POST", "/v1/jobs", caller.token(), request, "Idempotency-Key", key);
                        },
                        pool));
            start.countDown();
            List<HttpResponse<String>> responses =
                    futures.stream().map(CompletableFuture::join).toList();
            assertThat(responses)
                    .allSatisfy(response -> assertThat(response.statusCode())
                            .as(response.body())
                            .isEqualTo(201));
            assertThat(responses.stream().map(HttpResponse::body).distinct()).hasSize(1);
            UUID job = id(json.readTree(responses.getFirst().body()));
            assertThat(count(job, "jobs", "job_id")).isEqualTo(1);
            assertThat(count(job, "run_inflight", "target_id")).isEqualTo(1);
            assertThat(db(job).sql(
                                    "SELECT count(*) FROM scheduler.outbox WHERE run_id IN (SELECT run_id FROM scheduler.run_inflight WHERE target_id=:id)")
                            .param("id", job)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
        }
        problem(
                send(
                        "POST",
                        "/v1/jobs",
                        caller.token(),
                        request.replace("original", "changed"),
                        "Idempotency-Key",
                        key),
                409,
                "idempotency-key-reused");
        problem(send("POST", "/v1/jobs", caller.token(), request), 409, "job-name-taken");
    }

    @Test
    void updatesImmutableVersionsAndLeavesQueuedDispatchPinned() throws Exception {
        Caller caller = caller();
        UUID job = id(create(caller, "versioned", "{\"type\":\"IMMEDIATE\"}"));
        String request = body("versioned", INTERVAL).replace("\"priority\":7", "\"priority\":9");
        problem(send("PUT", "/v1/jobs/" + job, caller.token(), request), 428, "if-match-required");
        HttpResponse<String> updated = send("PUT", "/v1/jobs/" + job, caller.token(), request, "If-Match", "\"1\"");
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(updated.headers().firstValue("ETag")).contains("\"2\"");
        assertThat(count(job, "job_versions", "job_id")).isEqualTo(2);
        assertThat(db(job).sql("SELECT definition_version FROM scheduler.schedules WHERE schedule_id=:id")
                        .param("id", job)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        Dispatch dispatch = Dispatch.parseFrom(db(job).sql(
                        "SELECT payload FROM scheduler.outbox WHERE run_id IN (SELECT run_id FROM scheduler.run_inflight WHERE target_id=:id)")
                .param("id", job)
                .query(byte[].class)
                .single());
        assertThat(dispatch.getDefinitionVersion()).isEqualTo(1);
        problem(send("PUT", "/v1/jobs/" + job, caller.token(), request, "If-Match", "\"1\""), 412, "version-mismatch");
        problem(
                send(
                        "PUT",
                        "/v1/jobs/" + job,
                        caller.token(),
                        request.replace("versioned", "renamed"),
                        "If-Match",
                        "\"2\""),
                400,
                "name-immutable");
        assertThat(send("GET", "/v1/jobs/" + job, caller.token(), null)
                        .headers()
                        .firstValue("ETag"))
                .contains("\"2\"");
    }

    @Test
    void listsAcrossBothCellsWithBoundCursorFiltersAndTeamIsolation() {
        Caller caller = caller();
        String a = nameInCell(caller, 0), b = nameInCell(caller, 1);
        UUID first = id(create(caller, a, INTERVAL));
        UUID second = id(create(caller, b, INTERVAL));
        JsonNode page = ok(
                send("GET", "/v1/jobs?status=ACTIVE&endpoint=job-target&name=cell-&limit=1", caller.token(), null),
                200);
        assertThat(page.path("items").size()).isEqualTo(1);
        String cursor = page.path("nextCursor").asString();
        JsonNode next = ok(send("GET", "/v1/jobs?limit=1&cursor=" + cursor, caller.token(), null), 200);
        assertThat(next.path("items").size()).isEqualTo(1);
        assertThat(next.path("items").path(0).path("id").asString())
                .isNotEqualTo(page.path("items").path(0).path("id").asString());
        assertThat(next.path("nextCursor").isNull()).isTrue();
        problem(send("GET", "/v1/jobs?status=PAUSED&cursor=" + cursor, caller.token(), null), 400, "validation");
        Caller other = caller();
        problem(send("GET", "/v1/jobs?cursor=" + cursor, other.token(), null), 400, "validation");
        for (UUID job : List.of(first, second)) {
            problem(send("GET", "/v1/jobs/" + job, other.token(), null), 404, "not-found");
            problem(send("DELETE", "/v1/jobs/" + job, other.token(), null), 404, "not-found");
            problem(send("POST", "/v1/jobs/" + job + "/runnow", other.token(), "{}"), 404, "not-found");
            problem(send("GET", "/v1/jobs/" + job + "/runs", other.token(), null), 404, "not-found");
        }
    }

    @Test
    void batchReturnsPerItemResultsAndReplaysWithoutDuplicatingSuccessfulItems() {
        Caller caller = caller();
        String key = UUID.randomUUID().toString();
        String first = body(nameInCell(caller, 0), INTERVAL), second = body(nameInCell(caller, 1), INTERVAL);
        String batch = "{\"jobs\":[" + first + "," + first + "," + second + ",{\"name\":\"invalid\"}]}";
        HttpResponse<String> response = send("POST", "/v1/jobs:batch", caller.token(), batch, "Idempotency-Key", key);
        JsonNode results = ok(response, 207).path("results");
        assertThat(results.size()).isEqualTo(4);
        assertThat(results.path(0).path("status").asInt()).isEqualTo(201);
        assertThat(results.path(1).path("status").asInt()).isEqualTo(409);
        assertThat(results.path(2).path("status").asInt()).isEqualTo(201);
        assertThat(results.path(3).path("status").asInt()).isEqualTo(400);
        assertThat(send("POST", "/v1/jobs:batch", caller.token(), batch, "Idempotency-Key", key)
                        .body())
                .isEqualTo(response.body());
        assertThat(ok(send("GET", "/v1/jobs", caller.token(), null), 200)
                        .path("items")
                        .size())
                .isEqualTo(2);
        problem(send("POST", "/v1/jobs:batch", caller.token(), "{\"jobs\":[]}"), 400, "validation");
        problem(
                send(
                        "POST",
                        "/v1/jobs:batch",
                        caller.token(),
                        "{\"jobs\":[" + String.join(",", java.util.Collections.nCopies(1001, "{}")) + "]}"),
                400,
                "validation");
    }

    @Test
    void jobQuotaRejectsWholeOversizedBatchAndSingleCreateWith429() {
        Caller caller = caller();
        ApiTestFixtures.control()
                .sql("UPDATE scheduler.teams SET quotas='{" + "\"maxJobs\":1}'::jsonb WHERE team_id=:team")
                .param("team", caller.team().value())
                .update();
        problem(
                send(
                        "POST",
                        "/v1/jobs:batch",
                        caller.token(),
                        "{\"jobs\":[" + body("first", INTERVAL) + "," + body("second", INTERVAL) + "]}"),
                429,
                "quota-exceeded");
        assertThat(ok(send("GET", "/v1/jobs", caller.token(), null), 200)
                        .path("items")
                        .isEmpty())
                .isTrue();
        UUID first = id(create(caller, "first", INTERVAL));
        problem(send("POST", "/v1/jobs", caller.token(), body("second", INTERVAL)), 429, "quota-exceeded");
        assertThat(send("DELETE", "/v1/jobs/" + first, caller.token(), null).statusCode())
                .isEqualTo(204);
        create(caller, "second", INTERVAL);
    }

    @Test
    void pausePreservesQueuedWorkAndResumePreservesMissedOccurrencesForTheMisfirePolicy() {
        Caller caller = caller();
        UUID job = id(create(caller, "pause-resume", INTERVAL));
        UUID run = manual(caller, job, "{}");
        Instant missed = Instant.now().minusSeconds(180).truncatedTo(ChronoUnit.MICROS);
        db(job).sql(
                        "UPDATE scheduler.schedules SET next_fire_at=:missed,last_fired_local=TIMESTAMP '2026-10-24 10:00:00' WHERE schedule_id=:id")
                .param("missed", ApiTestFixtures.utc(missed))
                .param("id", job)
                .update();
        ok(send("POST", "/v1/jobs/" + job + "/pause", caller.token(), null), 200);
        assertThat(db(job).sql("SELECT state FROM scheduler.run_inflight WHERE run_id=:id")
                        .param("id", run)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        problem(send("POST", "/v1/jobs/" + job + "/pause", caller.token(), null), 409, "invalid-state");
        JsonNode resumed = ok(send("POST", "/v1/jobs/" + job + "/resume", caller.token(), null), 200);
        assertThat(Instant.parse(resumed.path("nextFireAt").asString())).isEqualTo(missed);
        assertThat(db(job).sql("SELECT last_fired_local IS NOT NULL FROM scheduler.schedules WHERE schedule_id=:id")
                        .param("id", job)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        problem(send("POST", "/v1/jobs/" + job + "/resume", caller.token(), null), 409, "invalid-state");
        db(job).sql("UPDATE scheduler.jobs SET status='AUTO_PAUSED' WHERE job_id=:id")
                .param("id", job)
                .update();
        ok(send("POST", "/v1/jobs/" + job + "/resume", caller.token(), null), 200);
    }

    @Test
    void deleteCancelsAllPendingStatesEmitsExactlyOneRunningCancelAndReleasesName() throws Exception {
        Caller caller = caller();
        UUID job = id(create(caller, "delete-me", INTERVAL));
        for (int state : List.of(1, 2, 4)) {
            UUID run = manual(caller, job, "{}");
            db(job).sql("UPDATE scheduler.run_inflight SET state=:state WHERE run_id=:run")
                    .param("state", state)
                    .param("run", run)
                    .update();
        }
        UUID running = manual(caller, job, "{}");
        db(job).sql("UPDATE scheduler.run_inflight SET state=3,fence=17 WHERE run_id=:run")
                .param("run", running)
                .update();
        assertThat(send("DELETE", "/v1/jobs/" + job, caller.token(), null).statusCode())
                .isEqualTo(204);
        problem(send("DELETE", "/v1/jobs/" + job, caller.token(), null), 404, "not-found");
        assertThat(count(job, "schedules", "schedule_id")).isZero();
        assertThat(count(job, "run_inflight", "target_id")).isEqualTo(1);
        assertThat(count(job, "run_history", "target_id")).isEqualTo(3);
        assertThat(db(job).sql("SELECT count(*) FROM scheduler.outbox WHERE run_id=:id AND topic=4")
                        .param("id", running)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        Cancel cancel =
                Cancel.parseFrom(db(job).sql("SELECT payload FROM scheduler.outbox WHERE run_id=:id AND topic=4")
                        .param("id", running)
                        .query(byte[].class)
                        .single());
        assertThat(ProtoIds.toUuid(cancel.getRunId())).isEqualTo(running);
        assertThat(cancel.getFence()).isEqualTo(17);
        assertThat(db(job).sql(
                                "SELECT count(*) FROM scheduler.outbox WHERE run_id IN (SELECT run_id FROM scheduler.run_history WHERE target_id=:id)")
                        .param("id", job)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(ok(send("GET", "/v1/jobs/" + job + "/runs?status=CANCELLED", caller.token(), null), 200)
                        .path("items")
                        .size())
                .isEqualTo(4);
        assertThat(id(create(caller, "delete-me", INTERVAL))).isNotEqualTo(job);
    }

    @Test
    void cancelKeepsJobVisibleAndCannotBeRepeatedOrManuallyRun() {
        Caller caller = caller();
        UUID job = id(create(caller, "cancel-me", INTERVAL));
        manual(caller, job, "{}");
        assertThat(ok(send("POST", "/v1/jobs/" + job + "/cancel", caller.token(), null), 200)
                        .path("status")
                        .asString())
                .isEqualTo("CANCELLED");
        assertThat(count(job, "run_inflight", "target_id")).isZero();
        assertThat(ok(send("GET", "/v1/jobs?status=CANCELLED", caller.token(), null), 200)
                        .path("items")
                        .size())
                .isEqualTo(1);
        problem(send("POST", "/v1/jobs/" + job + "/cancel", caller.token(), null), 409, "invalid-state");
        problem(send("POST", "/v1/jobs/" + job + "/runnow", caller.token(), "{}"), 409, "invalid-state");
    }

    @Test
    void manualRunOverridesOnlyThatRequestAndReplaysItsPinnedDispatch() throws Exception {
        Caller caller = caller();
        UUID job = id(create(caller, "manual", INTERVAL));
        String key = UUID.randomUUID().toString();
        String override =
                "{\"request\":{\"body\":null,\"query\":{\"mode\":\"full\"},\"headers\":{\"X-Mode\":\"new\"}}}";
        HttpResponse<String> response =
                send("POST", "/v1/jobs/" + job + "/runnow", caller.token(), override, "Idempotency-Key", key);
        UUID run = UUID.fromString(ok(response, 202).path("runId").asString());
        assertThat(send("POST", "/v1/jobs/" + job + "/runnow", caller.token(), override, "Idempotency-Key", key)
                        .body())
                .isEqualTo(response.body());
        assertThat(count(job, "run_inflight", "target_id")).isEqualTo(1);
        Dispatch dispatch = Dispatch.parseFrom(db(job).sql("SELECT payload FROM scheduler.outbox WHERE run_id=:run")
                .param("run", run)
                .query(byte[].class)
                .single());
        assertThat(dispatch.getRequest().getBody().toStringUtf8()).isEqualTo("null");
        assertThat(dispatch.getRequest().getPathParamsMap()).containsEntry("orderId", "42");
        assertThat(dispatch.getRequest().getQueryList()).anySatisfy(value -> {
            assertThat(value.getName()).isEqualTo("mode");
            assertThat(value.getValue()).isEqualTo("full");
        });
        assertThat(ok(send("GET", "/v1/jobs/" + job, caller.token(), null), 200)
                        .path("request")
                        .path("body")
                        .path("mode")
                        .asString())
                .isEqualTo("original");
        problem(
                send(
                        "POST",
                        "/v1/jobs/" + job + "/runnow",
                        caller.token(),
                        "{\"request\":{\"headers\":{\"Host\":\"external\"}}}"),
                400,
                "validation");
        manual(caller, job, null);
    }

    @Test
    void intervalBackfillIsBoundedDelayedPacedAndSkipsAlreadyCreatedOccurrences() {
        Caller caller = caller();
        UUID job = id(create(caller, "backfill", "{\"type\":\"INTERVAL\",\"everySec\":1}"));
        Instant anchor = Instant.now().minusSeconds(2000).truncatedTo(ChronoUnit.MICROS);
        db(job).sql("UPDATE scheduler.jobs SET created_at=:at WHERE job_id=:id")
                .param("at", ApiTestFixtures.utc(anchor))
                .param("id", job)
                .update();
        String request = range(anchor, anchor.plusSeconds(1500), true);
        assertThat(ok(send("POST", "/v1/jobs/" + job + "/backfill", caller.token(), request), 202)
                        .path("occurrences")
                        .asInt())
                .isEqualTo(1501);
        assertThat(count(job, "run_inflight", "target_id")).isZero();
        request = range(anchor, anchor.plusSeconds(1500), false);
        assertThat(ok(send("POST", "/v1/jobs/" + job + "/backfill", caller.token(), request), 202)
                        .path("occurrences")
                        .asInt())
                .isEqualTo(1501);
        assertThat(db(job).sql(
                                "SELECT count(*) FROM scheduler.run_inflight WHERE target_id=:id AND state=4 AND trigger=4")
                        .param("id", job)
                        .query(Long.class)
                        .single())
                .isEqualTo(1501);
        double spacing = db(job).sql(
                        "SELECT EXTRACT(EPOCH FROM (max(due_at)-min(due_at)))::double precision FROM scheduler.run_inflight WHERE target_id=:id")
                .param("id", job)
                .query(Double.class)
                .single();
        assertThat(spacing).isGreaterThanOrEqualTo(15.0);
        assertThat(ok(send("POST", "/v1/jobs/" + job + "/backfill", caller.token(), request), 202)
                        .path("occurrences")
                        .asInt())
                .isZero();
        problem(
                send(
                        "POST",
                        "/v1/jobs/" + job + "/backfill",
                        caller.token(),
                        range(anchor, anchor.plusSeconds(10000), true)),
                400,
                "range-too-large");
        UUID immediate = id(create(caller, "not-recurring", "{\"type\":\"IMMEDIATE\"}"));
        problem(send("POST", "/v1/jobs/" + immediate + "/backfill", caller.token(), request), 409, "not-recurring");
    }

    @Test
    void cronBackfillCanRecoverOccurrencesBeforeTheJobWasCreated() {
        Caller caller = caller();
        UUID job = id(create(
                caller, "cron-backfill", "{\"type\":\"CRON\",\"expression\":\"* * * * *\",\"timezone\":\"UTC\"}"));
        Instant from = Instant.now().truncatedTo(ChronoUnit.MINUTES).minusSeconds(180);
        assertThat(ok(
                                send(
                                        "POST",
                                        "/v1/jobs/" + job + "/backfill",
                                        caller.token(),
                                        range(from, from.plusSeconds(120), false)),
                                202)
                        .path("occurrences")
                        .asInt())
                .isEqualTo(3);
    }

    @Test
    void runsUseCursorAndStatusAndTimeFiltersAcrossInflightAndHistory() {
        Caller caller = caller();
        UUID job = id(create(caller, "history", INTERVAL));
        manual(caller, job, "{}");
        manual(caller, job, "{}");
        JsonNode first = ok(send("GET", "/v1/jobs/" + job + "/runs?limit=1&status=QUEUED", caller.token(), null), 200);
        String cursor = first.path("nextCursor").asString();
        JsonNode second =
                ok(send("GET", "/v1/jobs/" + job + "/runs?limit=1&cursor=" + cursor, caller.token(), null), 200);
        assertThat(second.path("items").path(0).path("runId").asString())
                .isNotEqualTo(first.path("items").path(0).path("runId").asString());
        problem(
                send("GET", "/v1/jobs/" + job + "/runs?status=RUNNING&cursor=" + cursor, caller.token(), null),
                400,
                "validation");
        String old = Instant.now().minusSeconds(8 * 86400).toString();
        problem(send("GET", "/v1/jobs/" + job + "/runs?from=" + old, caller.token(), null), 400, "validation");
        problem(send("GET", "/v1/jobs/" + job + "/runs?status=INVALID", caller.token(), null), 400, "validation");
        ok(send("POST", "/v1/jobs/" + job + "/cancel", caller.token(), null), 200);
        assertThat(ok(send("GET", "/v1/jobs/" + job + "/runs?status=CANCELLED", caller.token(), null), 200)
                        .path("items")
                        .size())
                .isEqualTo(2);
    }

    @Test
    void inactiveEndpointHoldsSchedulesAndRejectsDirectExecutionAndUnknownEndpoint() {
        Caller caller = caller();
        JsonNode endpoint = ok(send("GET", "/v1/endpoints", caller.token(), null), 200)
                .path("items")
                .path(0);
        UUID job = id(create(caller, "held", INTERVAL));
        ok(send("POST", "/v1/endpoints/" + endpoint.path("id").asString() + "/suspend", caller.token(), null), 200);
        create(caller, "created-held", INTERVAL);
        assertThat(db(job).sql("SELECT status FROM scheduler.schedules WHERE schedule_id=:id")
                        .param("id", job)
                        .query(Integer.class)
                        .single())
                .isEqualTo(3);
        problem(send("POST", "/v1/jobs/" + job + "/runnow", caller.token(), "{}"), 409, "invalid-state");
        problem(
                send("POST", "/v1/jobs", caller.token(), body("immediate-held", "{\"type\":\"IMMEDIATE\"}")),
                409,
                "invalid-state");
        problem(
                send(
                        "POST",
                        "/v1/jobs",
                        caller.token(),
                        body("missing", INTERVAL).replace("job-target", "missing-target")),
                400,
                "endpoint-not-found");
    }

    @Test
    void failedImmediateMaterializationRollsBackJobVersionRunAndOutbox() {
        Caller caller = caller();
        Instant now = Instant.now();
        JobSpec spec = JobSpec.parse(json.readTree(body("rollback", "{\"type\":\"IMMEDIATE\"}")), now, json);
        var endpoint = endpoints.find(caller.team(), "job-target").orElseThrow();
        UUID id = ids.newJobId(ShardKey.of(caller.team().value(), spec.name())).value();
        var call = JobCallBuilder.build(endpoint.spec(), spec.request(), json);
        var rolledBackRun = new java.util.concurrent.atomic.AtomicReference<UUID>();
        assertThatThrownBy(() -> cells.forId(id).transactions().execute(jdbc -> {
                    jobs.create(
                            caller.team(),
                            id,
                            "user:" + caller.user().value(),
                            spec,
                            endpoint,
                            JobSchedule.from(spec, now),
                            now,
                            call);
                    rolledBackRun.set(db(id).sql("SELECT run_id FROM scheduler.run_inflight WHERE target_id=:id")
                            .param("id", id)
                            .query(UUID.class)
                            .single());
                    throw new IllegalStateException("force rollback after outbox insertion");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count(id, "jobs", "job_id")).isZero();
        assertThat(count(id, "job_versions", "job_id")).isZero();
        assertThat(count(id, "run_inflight", "target_id")).isZero();
        assertThat(jobs.find(caller.team(), id)).isEmpty();
        assertThat(db(id).sql("SELECT count(*) FROM scheduler.outbox WHERE run_id=:run")
                        .param("run", rolledBackRun.get())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void runHistoryReportsOnlyTheLatestFinalAttemptAndPrefersAnActiveRedrive() {
        Caller caller = caller();
        UUID job = id(create(caller, "redrive-history", INTERVAL));
        UUID run = manual(caller, job, "{}");
        archive(job, run, 1, 3);
        archive(job, run, 2, 1);
        db(job).sql("UPDATE scheduler.run_inflight SET attempt=3 WHERE run_id=:id")
                .param("id", run)
                .update();
        JsonNode active = ok(send("GET", "/v1/jobs/" + job + "/runs", caller.token(), null), 200)
                .path("items");
        assertThat(active.size()).isEqualTo(1);
        assertThat(active.path(0).path("status").asString()).isEqualTo("QUEUED");
        db(job).sql("DELETE FROM scheduler.run_inflight WHERE run_id=:id")
                .param("id", run)
                .update();
        JsonNode completed = ok(send("GET", "/v1/jobs/" + job + "/runs", caller.token(), null), 200)
                .path("items");
        assertThat(completed.size()).isEqualTo(1);
        assertThat(completed.path(0).path("attempt").asInt()).isEqualTo(2);
        assertThat(completed.path(0).path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(ok(send("GET", "/v1/jobs/" + job, caller.token(), null), 200)
                        .path("lastRun")
                        .path("status")
                        .asString())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void manualExecutionQuotaReturns429WithoutCreatingARunOrDispatch() {
        Caller caller = caller();
        UUID job = id(create(caller, "execution-quota", INTERVAL));
        ApiTestFixtures.control()
                .sql("UPDATE scheduler.teams SET quotas=CAST(:quotas AS jsonb) WHERE team_id=:team")
                .param("quotas", "{\"maxExecPerSec\":1}")
                .param("team", caller.team().value())
                .update();
        Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        for (int offset : List.of(1, 2)) {
            db(job).sql(
                            "INSERT INTO scheduler.team_stats_minute(team_id,minute,node_id,attempts) VALUES (:team,:minute,'acceptance',60)")
                    .param("team", caller.team().value())
                    .param("minute", ApiTestFixtures.utc(minute.minusSeconds(60L * offset)))
                    .update();
        }
        problem(send("POST", "/v1/jobs/" + job + "/runnow", caller.token(), "{}"), 429, "quota-exceeded");
        assertThat(count(job, "run_inflight", "target_id")).isZero();
    }

    @Test
    void batchThatExactlyFitsTheJobQuotaSucceedsInBothCells() {
        Caller caller = caller();
        ApiTestFixtures.control()
                .sql("UPDATE scheduler.teams SET quotas=CAST(:quotas AS jsonb) WHERE team_id=:team")
                .param("quotas", "{\"maxJobs\":2}")
                .param("team", caller.team().value())
                .update();
        String batch = "{\"jobs\":[" + body(nameInCell(caller, 0), INTERVAL) + ","
                + body(nameInCell(caller, 1), INTERVAL) + "]}";
        JsonNode result =
                ok(send("POST", "/v1/jobs:batch", caller.token(), batch), 207).path("results");
        assertThat(result.path(0).path("status").asInt()).isEqualTo(201);
        assertThat(result.path(1).path("status").asInt()).isEqualTo(201);
    }

    @Test
    void concurrentDeletesOfARunningJobEmitOneCancelMessage() throws Exception {
        Caller caller = caller();
        UUID job = id(create(caller, "concurrent-delete", INTERVAL));
        UUID run = manual(caller, job, "{}");
        db(job).sql("UPDATE scheduler.run_inflight SET state=3 WHERE run_id=:id")
                .param("id", run)
                .update();
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
                            return send("DELETE", "/v1/jobs/" + job, caller.token(), null);
                        },
                        pool));
            start.countDown();
            List<HttpResponse<String>> responses =
                    futures.stream().map(CompletableFuture::join).toList();
            assertThat(responses.stream().filter(r -> r.statusCode() == 204).count())
                    .isEqualTo(1);
            assertThat(responses.stream().filter(r -> r.statusCode() == 404).count())
                    .isEqualTo(3);
        }
        assertThat(db(job).sql("SELECT count(*) FROM scheduler.outbox WHERE run_id=:id AND topic=4")
                        .param("id", run)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void concurrentDistinctCreatesCannotOversubscribeTheTeamJobQuota() throws Exception {
        Caller caller = caller();
        ApiTestFixtures.control()
                .sql("UPDATE scheduler.teams SET quotas=CAST(:quotas AS jsonb) WHERE team_id=:team")
                .param("quotas", "{\"maxJobs\":1}")
                .param("team", caller.team().value())
                .update();
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                String name = "concurrent-quota-" + i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                start.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                            return send("POST", "/v1/jobs", caller.token(), body(name, INTERVAL));
                        },
                        pool));
            }
            start.countDown();
            List<HttpResponse<String>> responses =
                    futures.stream().map(CompletableFuture::join).toList();
            assertThat(responses.stream().filter(r -> r.statusCode() == 201).count())
                    .isEqualTo(1);
            assertThat(responses.stream().filter(r -> r.statusCode() == 429).count())
                    .isEqualTo(7);
        }
    }

    @Test
    void quotaAdmissionReleasesItsSessionLockWhenTheCellWorkFails() throws Exception {
        Caller caller = caller();
        String key = "kairos:job-create:" + caller.team().value();
        // Reserve an independent session before the guard borrows its own pooled connection.
        try (var independent = databases.control().dataSource().getConnection()) {
            assertThatThrownBy(() -> creationGuard.execute(caller.team(), () -> {
                        throw new IllegalStateException("forced cell failure");
                    }))
                    .isInstanceOf(IllegalStateException.class);
            try (var statement = independent.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?,0))")) {
                statement.setString(1, key);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                }
            } finally {
                try (var statement = independent.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?,0))")) {
                    statement.setString(1, key);
                    statement.execute();
                }
            }
        }
        create(caller, "after-failed-admission", INTERVAL);
    }

    @Test
    void backfillRechecksCancellationUnderTheJobLockAfterItsDefinitionWasRead() {
        Caller caller = caller();
        UUID job = id(create(caller, "cancel-before-backfill", INTERVAL));
        var stored = jobs.find(caller.team(), job).orElseThrow();
        JobSpec spec = JobSpec.parse(stored.spec(), stored.createdAt(), json);
        var endpoint = endpoints.find(caller.team(), spec.endpoint()).orElseThrow();
        ok(send("POST", "/v1/jobs/" + job + "/cancel", caller.token(), null), 200);
        assertThatThrownBy(() -> jobs.backfill(
                        caller.team(),
                        job,
                        stored.version(),
                        spec,
                        endpoint,
                        List.of(stored.createdAt()),
                        JobCallBuilder.build(endpoint.spec(), spec.request(), json),
                        100))
                .isInstanceOf(dev.kairos.domain.error.ConflictException.class);
        assertThat(count(job, "run_inflight", "target_id")).isZero();
    }

    private void archive(UUID job, UUID run, int attempt, int outcome) {
        db(job).sql("""
                INSERT INTO scheduler.run_history(scheduled_at,run_id,attempt,infra_attempt,team_id,target_type,
                    definition_id,target_id,task_key,endpoint_id,trigger,outcome,final,finished_at)
                SELECT scheduled_at,run_id,:attempt,0,team_id,target_type,definition_id,target_id,task_key,
                    endpoint_id,trigger,:outcome,true,now()
                FROM scheduler.run_inflight WHERE run_id=:run
                """)
                .param("attempt", attempt)
                .param("outcome", outcome)
                .param("run", run)
                .update();
    }

    private Caller caller() {
        Caller caller = newUser(ApiTestFixtures.insertTeam());
        ok(send("POST", "/v1/endpoints", caller.token(), ENDPOINT), 201);
        return caller;
    }

    private JsonNode create(Caller caller, String name, String schedule) {
        return ok(send("POST", "/v1/jobs", caller.token(), body(name, schedule)), 201);
    }

    private UUID manual(Caller caller, UUID job, String request) {
        return UUID.fromString(ok(send("POST", "/v1/jobs/" + job + "/runnow", caller.token(), request), 202)
                .path("runId")
                .asString());
    }

    private static String body(String name, String schedule) {
        return "{\"name\":\"" + name + "\",\"schedule\":" + schedule
                + ",\"endpoint\":\"job-target\",\"request\":{\"pathParams\":{\"orderId\":\"42\"},\"body\":{\"mode\":\"original\"}},\"priority\":7}";
    }

    private static String range(Instant from, Instant to, boolean dryRun) {
        return "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"dryRun\":" + dryRun + "}";
    }

    private JsonNode ok(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }

    private void problem(HttpResponse<String> response, int status, String code) {
        assertThat(ok(response, status).path("type").asString()).isEqualTo("/errors/" + code);
    }

    private static UUID id(JsonNode value) {
        return UUID.fromString(value.path("id").asString());
    }

    private JdbcClient db(UUID id) {
        return cells.forId(id).jdbc();
    }

    private long count(UUID job, String table, String column) {
        return db(job).sql("SELECT count(*) FROM scheduler." + table + " WHERE " + column + "=:id")
                .param("id", job)
                .query(Long.class)
                .single();
    }

    private static String nameInCell(Caller caller, int cell) {
        for (int i = 0; ; i++) {
            String name = "cell-" + cell + "-" + i;
            if (ShardKey.of(caller.team().value(), name).value() / 128 == cell) return name;
        }
    }
}
