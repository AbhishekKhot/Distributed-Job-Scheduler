package dev.kairos.bench.scenario;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Drives named public API scenarios against the packaged scheduler stack. */
public final class ScenarioRunner {
    public static final List<String> NAMES = List.of(
            "onboarding", "job-lifecycle", "retry-to-dlq", "workflow-data-passing", "isolation", "auto-protection");

    private final ScenarioHttp api;
    private final ScenarioHttp target;
    private final String targetUrl;

    public ScenarioRunner(ScenarioHttp api, ScenarioHttp target, String targetUrl) {
        this.api = api;
        this.target = target;
        this.targetUrl = targetUrl;
    }

    public Map<String, Object> run(String name) throws Exception {
        if (!NAMES.contains(name)) throw new IllegalArgumentException("Unknown scenario: " + name);
        Instant start = Instant.now();
        Map<String, Object> details = switch (name) {
            case "onboarding" -> onboarding();
            case "job-lifecycle" -> jobLifecycle();
            case "retry-to-dlq" -> retryToDlq();
            case "workflow-data-passing" -> workflowDataPassing();
            case "isolation" -> isolation();
            case "auto-protection" -> autoProtection();
            default -> throw new IllegalStateException(name);
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", name);
        result.put("passed", true);
        result.put("durationMs", Duration.between(start, Instant.now()).toMillis());
        result.putAll(details);
        return result;
    }

    private Map<String, Object> onboarding() throws Exception {
        ScenarioEnvironment team = ScenarioEnvironment.create(api, target, targetUrl);
        String secondEmail = "scenario-member-" + team.suffix + "@kairos.test";
        String secondPassword = "A1!" + team.suffix + "member";
        api.post("v1/auth/signup", Map.of("email", secondEmail, "password", secondPassword), null)
                .expect(201);
        String secondId = api.post("v1/team/members", Map.of("email", secondEmail), team.userToken)
                .expect(201)
                .path("userId")
                .asString();
        String memberToken = api.post("v1/auth/login", Map.of("email", secondEmail, "password", secondPassword), null)
                .expect(200)
                .path("accessToken")
                .asString();
        var teamByKey = api.get("v1/team", team.apiKey).expect(200);
        var teamByMember = api.get("v1/team", memberToken).expect(200);
        ScenarioEnvironment.require(
                team.teamId.equals(teamByKey.path("teamId").asString()), "API key could not access the team");
        ScenarioEnvironment.require(
                team.teamId.equals(teamByMember.path("teamId").asString()), "New member did not join the team");
        boolean listed = false;
        for (var member : api.get("v1/team/members", team.userToken).expect(200).path("items"))
            if (secondId.equals(member.path("userId").asString())) listed = true;
        ScenarioEnvironment.require(listed, "New member missing from member list");
        return Map.of("teamId", team.teamId, "memberId", secondId);
    }

    private Map<String, Object> jobLifecycle() throws Exception {
        ScenarioEnvironment team = ScenarioEnvironment.create(api, target, targetUrl);
        String endpoint = "scenario-lifecycle-" + team.suffix;
        String endpointId = team.endpoint("lifecycle", "/behave/healthy", Map.of());
        Map<String, String> jobs = new LinkedHashMap<>();
        jobs.put("immediate", team.createJob("immediate", endpoint, Map.of("type", "IMMEDIATE")));
        jobs.put(
                "one-time",
                team.createJob(
                        "one-time",
                        endpoint,
                        Map.of(
                                "type",
                                "ONE_TIME",
                                "at",
                                Instant.now().plusSeconds(5).toString())));
        jobs.put("interval", team.createJob("interval", endpoint, Map.of("type", "INTERVAL", "everySec", 2)));
        jobs.put(
                "cron",
                team.createJob("cron", endpoint, Map.of("type", "CRON", "expression", "* * * * *", "timezone", "UTC")));
        List<String> runs = new ArrayList<>();
        for (var entry : jobs.entrySet()) {
            String run = team.awaitJobRun(entry.getValue(), "SUCCEEDED", Duration.ofSeconds(90));
            runs.add(run);
        }
        String interval = jobs.get("interval");
        api.post("v1/jobs/" + interval + "/pause", Map.of(), team.apiKey).expect(200);
        ScenarioEnvironment.require(
                "PAUSED"
                        .equals(api.get("v1/jobs/" + interval, team.apiKey)
                                .expect(200)
                                .path("status")
                                .asString()),
                "Interval job did not pause");
        api.post("v1/jobs/" + interval + "/resume", Map.of(), team.apiKey).expect(200);
        var current = api.get("v1/jobs/" + interval, team.apiKey);
        current.expect(200);
        Map<String, Object> update =
                new LinkedHashMap<>(team.job("interval", endpoint, Map.of("type", "INTERVAL", "everySec", 3)));
        api.put("v1/jobs/" + interval, update, team.apiKey, current.etag()).expect(200);
        for (String id : jobs.values()) api.delete("v1/jobs/" + id, team.apiKey).expect(204);
        var counts = target.get("stats/snapshot", null).expect(200).path("counts");
        for (String run : runs)
            ScenarioEnvironment.require(counts.path(run).asLong() >= 1, "Mock target did not record run " + run);
        return Map.of("jobs", jobs, "runs", runs, "endpointId", endpointId);
    }

    private Map<String, Object> retryToDlq() throws Exception {
        ScenarioEnvironment team = ScenarioEnvironment.create(api, target, targetUrl);
        String endpoint = "scenario-retry-" + team.suffix;
        Map<String, Object> retry =
                Map.of("maxRetries", 5, "strategy", "FIXED", "baseDelaySec", 1, "maxDelaySec", 1, "maxAgeSec", 60);
        String endpointId = team.endpoint(
                "retry", "/behave/retryable", Map.of("retryPolicy", retry, "suspendPolicy", Map.of("enabled", false)));
        String job = team.createJob("retry", endpoint, Map.of("type", "IMMEDIATE"));
        String run = team.awaitJobRun(job, "DEAD_LETTERED", Duration.ofMinutes(2));
        var dead = api.get("v1/runs/" + run, team.apiKey).expect(200);
        ScenarioEnvironment.require(dead.path("attempt").asInt() == 6, "Retry policy did not make six attempts");
        String dlq = ScenarioEnvironment.await(Duration.ofSeconds(20), () -> {
            for (var item :
                    api.get("v1/dlq?status=OPEN", team.apiKey).expect(200).path("items"))
                if (run.equals(item.path("runId").asString()))
                    return item.path("dlqId").asString();
            return null;
        });
        api.post(
                        "v1/dlq/" + dlq + "/acknowledge",
                        Map.of("assignee", "scenario-operator", "notes", "Target recovery is in progress"),
                        team.apiKey)
                .expect(200);
        var endpointBefore = api.get("v1/endpoints/" + endpointId, team.apiKey);
        endpointBefore.expect(200);
        api.put(
                        "v1/endpoints/" + endpointId,
                        team.endpointBody(
                                endpoint,
                                "/behave/healthy",
                                Map.of("retryPolicy", retry, "suspendPolicy", Map.of("enabled", false))),
                        team.apiKey,
                        endpointBefore.etag())
                .expect(200);
        api.post("v1/dlq/" + dlq + "/redrive", Map.of("mode", "CURRENT"), team.apiKey)
                .expect(202);
        ScenarioEnvironment.await(
                Duration.ofSeconds(45),
                () -> "SUCCEEDED"
                                .equals(api.get("v1/runs/" + run, team.apiKey)
                                        .expect(200)
                                        .path("status")
                                        .asString())
                        ? true
                        : null);
        String discardEndpoint = "scenario-discard-" + team.suffix;
        team.endpoint("discard", "/behave/client-error", Map.of("suspendPolicy", Map.of("enabled", false)));
        String discardJob = team.createJob("discard", discardEndpoint, Map.of("type", "IMMEDIATE"));
        String discardRun = team.awaitJobRun(discardJob, "DEAD_LETTERED", Duration.ofSeconds(45));
        String discardDlq = ScenarioEnvironment.await(Duration.ofSeconds(20), () -> {
            for (var item :
                    api.get("v1/dlq?status=OPEN", team.apiKey).expect(200).path("items"))
                if (discardRun.equals(item.path("runId").asString()))
                    return item.path("dlqId").asString();
            return null;
        });
        api.post(
                        "v1/dlq/" + discardDlq + "/discard",
                        Map.of("reason", "Accepted permanent client failure"),
                        team.apiKey)
                .expect(200);
        ScenarioEnvironment.require(
                "DISCARDED"
                        .equals(api.get("v1/dlq/" + discardDlq, team.apiKey)
                                .expect(200)
                                .path("status")
                                .asString()),
                "DLQ discard was not persisted");
        return Map.of("retriedRun", run, "attempts", 6, "redrivenDlq", dlq, "discardedDlq", discardDlq);
    }

    private Map<String, Object> workflowDataPassing() throws Exception {
        ScenarioEnvironment team = ScenarioEnvironment.create(api, target, targetUrl);
        String source = "scenario-source-" + team.suffix;
        String branch = "scenario-branch-" + team.suffix;
        String sink = "scenario-sink-" + team.suffix;
        team.endpoint(
                "source",
                "/behave/healthy",
                Map.of(
                        "outputs",
                        Map.of("ok", Map.of("from", "BODY", "path", "$.ok", "required", true, "sensitive", false))));
        team.endpoint("branch", "/behave/healthy", Map.of());
        String expectedBody = "{\"fromA\":true}";
        String expectedHash = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(expectedBody.getBytes(StandardCharsets.UTF_8)));
        team.endpoint(
                "sink",
                "/behave/healthy",
                Map.of(
                        "defaultHeaders",
                        Map.of("X-Mock-Expect-Header", "X-From-A=true", "X-Mock-Expect-Body-Sha256", expectedHash)));
        Map<String, Object> tasks = new LinkedHashMap<>();
        tasks.put("A", Map.of("endpoint", source));
        tasks.put("B", Map.of("endpoint", branch, "dependsOn", List.of("A")));
        tasks.put("C", Map.of("endpoint", branch, "dependsOn", List.of("A")));
        tasks.put(
                "D",
                Map.of(
                        "endpoint",
                        sink,
                        "dependsOn",
                        List.of("B", "C"),
                        "request",
                        Map.of(
                                "headers",
                                Map.of("X-From-A", "{{ tasks.A.outputs.ok }}"),
                                "body",
                                Map.of("fromA", "{{ tasks.A.outputs.ok }}"))));
        String workflow = api.post(
                        "v1/workflows",
                        Map.of(
                                "name",
                                "scenario-diamond-" + team.suffix,
                                "schedule",
                                Map.of("type", "IMMEDIATE"),
                                "tasks",
                                tasks,
                                "priority",
                                5,
                                "onTaskFailure",
                                "FAIL_FAST"),
                        team.apiKey)
                .expect(201)
                .path("id")
                .asString();
        String workflowRun = ScenarioEnvironment.await(Duration.ofSeconds(90), () -> {
            for (var item : api.get("v1/workflows/" + workflow + "/runs", team.apiKey)
                    .expect(200)
                    .path("items"))
                if ("SUCCEEDED".equals(item.path("status").asString()))
                    return item.path("workflowRunId").asString();
            return null;
        });
        var detail = api.get("v1/workflow-runs/" + workflowRun, team.apiKey).expect(200);
        ScenarioEnvironment.require(
                detail.path("counts").path("succeeded").asInt() == 4,
                "Diamond workflow did not complete all four tasks");
        String sinkRun = null;
        for (var task : detail.path("tasks"))
            if ("D".equals(task.path("taskKey").asString()))
                sinkRun = task.path("runId").asString();
        ScenarioEnvironment.require(sinkRun != null, "Diamond sink task missing");
        ScenarioEnvironment.require(
                target.get("stats/snapshot", null)
                                .expect(200)
                                .path("counts")
                                .path(sinkRun)
                                .asLong()
                        == 1,
                "Mock target did not accept the rendered header and body");
        return Map.of("workflowId", workflow, "workflowRunId", workflowRun, "sinkRunId", sinkRun);
    }

    private Map<String, Object> isolation() throws Exception {
        ScenarioEnvironment owner = ScenarioEnvironment.create(api, target, targetUrl);
        String ownerUserId =
                api.get("v1/me", owner.userToken).expect(200).path("userId").asString();
        String secretName = "scenario-secret-" + owner.suffix;
        api.post(
                        "v1/secrets",
                        Map.of("name", secretName, "kind", "BEARER", "value", Map.of("token", "scenario-token")),
                        owner.apiKey)
                .expect(201);
        String endpointName = "scenario-owner-" + owner.suffix;
        String endpoint = owner.endpoint("owner", "/behave/healthy", Map.of());
        String job = owner.createJob("owner", endpointName, Map.of("type", "IMMEDIATE"));
        String run = owner.awaitJobRun(job, "SUCCEEDED", Duration.ofSeconds(45));
        String workflow = api.post(
                        "v1/workflows",
                        Map.of(
                                "name", "scenario-owner-workflow-" + owner.suffix,
                                "schedule", Map.of("type", "IMMEDIATE"),
                                "tasks", Map.of("A", Map.of("endpoint", endpointName))),
                        owner.apiKey)
                .expect(201)
                .path("id")
                .asString();
        String workflowRun = ScenarioEnvironment.await(Duration.ofSeconds(45), () -> {
            for (var item : api.get("v1/workflows/" + workflow + "/runs", owner.apiKey)
                    .expect(200)
                    .path("items")) return item.path("workflowRunId").asString();
            return null;
        });
        String failedEndpointName = "scenario-owner-fail-" + owner.suffix;
        owner.endpoint("owner-fail", "/behave/client-error", Map.of());
        String failedJob = owner.createJob("owner-fail", failedEndpointName, Map.of("type", "IMMEDIATE"));
        String failedRun = owner.awaitJobRun(failedJob, "DEAD_LETTERED", Duration.ofSeconds(45));
        String dlq = ScenarioEnvironment.await(Duration.ofSeconds(20), () -> {
            for (var item :
                    api.get("v1/dlq?status=OPEN", owner.apiKey).expect(200).path("items"))
                if (failedRun.equals(item.path("runId").asString()))
                    return item.path("dlqId").asString();
            return null;
        });
        ScenarioEnvironment other = ScenarioEnvironment.create(api, target, targetUrl);
        List<Probe> probes = new ArrayList<>();
        String endpointPath = "v1/endpoints/" + endpoint;
        probes.add(new Probe("GET", endpointPath, null, Map.of()));
        probes.add(new Probe(
                "PUT",
                endpointPath,
                owner.endpointBody(endpointName, "/behave/healthy", Map.of()),
                Map.of("If-Match", "\"1\"")));
        probes.add(new Probe("DELETE", endpointPath, null, Map.of()));
        probes.add(new Probe("POST", endpointPath + ":test", Map.of("pathParams", Map.of()), Map.of()));
        probes.add(new Probe("POST", endpointPath + "/suspend", Map.of(), Map.of()));
        probes.add(new Probe("POST", endpointPath + "/resume", Map.of(), Map.of()));
        probes.add(new Probe("GET", endpointPath + "/health", null, Map.of()));
        String jobPath = "v1/jobs/" + job;
        probes.add(new Probe("GET", jobPath, null, Map.of()));
        probes.add(new Probe(
                "PUT",
                jobPath,
                owner.job("owner", endpointName, Map.of("type", "INTERVAL", "everySec", 60)),
                Map.of("If-Match", "\"1\"")));
        probes.add(new Probe("DELETE", jobPath, null, Map.of()));
        for (String action : List.of("pause", "resume", "cancel", "runnow"))
            probes.add(new Probe("POST", jobPath + "/" + action, Map.of(), Map.of()));
        probes.add(new Probe(
                "POST",
                jobPath + "/backfill",
                Map.of(
                        "from",
                        Instant.now().minusSeconds(120).toString(),
                        "to",
                        Instant.now().minusSeconds(60).toString(),
                        "dryRun",
                        true),
                Map.of()));
        probes.add(new Probe("GET", jobPath + "/runs", null, Map.of()));
        String workflowPath = "v1/workflows/" + workflow;
        probes.add(new Probe("GET", workflowPath, null, Map.of()));
        probes.add(new Probe(
                "PUT",
                workflowPath,
                Map.of(
                        "name", "scenario-owner-workflow-" + owner.suffix,
                        "schedule", Map.of("type", "IMMEDIATE"),
                        "tasks", Map.of("A", Map.of("endpoint", endpointName))),
                Map.of("If-Match", "\"1\"")));
        probes.add(new Probe("DELETE", workflowPath, null, Map.of()));
        for (String action : List.of("pause", "resume", "cancel", "runnow"))
            probes.add(new Probe("POST", workflowPath + "/" + action, Map.of(), Map.of()));
        probes.add(new Probe("GET", workflowPath + "/runs", null, Map.of()));
        String workflowRunPath = "v1/workflow-runs/" + workflowRun;
        probes.add(new Probe("GET", workflowRunPath, null, Map.of()));
        probes.add(new Probe("POST", workflowRunPath + "/cancel", Map.of(), Map.of()));
        probes.add(new Probe("POST", workflowRunPath + "/retry", Map.of("from", "FAILED_TASKS"), Map.of()));
        probes.add(new Probe("GET", "v1/runs/" + run, null, Map.of()));
        probes.add(new Probe("POST", "v1/runs/" + run + "/cancel", Map.of(), Map.of()));
        String dlqPath = "v1/dlq/" + dlq;
        probes.add(new Probe("GET", dlqPath, null, Map.of()));
        probes.add(new Probe(
                "POST",
                dlqPath + "/acknowledge",
                Map.of("assignee", "scenario-operator", "notes", "isolation"),
                Map.of()));
        probes.add(new Probe("POST", dlqPath + "/redrive", Map.of("mode", "ORIGINAL"), Map.of()));
        probes.add(new Probe("POST", dlqPath + "/discard", Map.of("reason", "isolation"), Map.of()));
        probes.add(new Probe("DELETE", "v1/api-keys/" + owner.apiKeyId, null, Map.of()));
        probes.add(new Probe("DELETE", "v1/team/members/" + ownerUserId, null, Map.of()));
        probes.add(new Probe("PUT", "v1/secrets/" + secretName, Map.of("value", Map.of("token", "other")), Map.of()));
        probes.add(new Probe("DELETE", "v1/secrets/" + secretName, null, Map.of()));
        probes.add(new Probe("DELETE", "v1/ca-bundles/" + owner.caName, null, Map.of()));
        for (Probe probe : probes) {
            var response = api.request(probe.method(), probe.path(), probe.body(), other.userToken, probe.headers());
            ScenarioEnvironment.require(
                    response.status() == 404,
                    probe.method() + " /" + probe.path() + " revealed another team's ID (HTTP " + response.status()
                            + ")");
        }
        List<String> lists = List.of(
                "v1/team/members",
                "v1/api-keys",
                "v1/secrets",
                "v1/ca-bundles",
                "v1/endpoints",
                "v1/jobs",
                "v1/workflows",
                "v1/runs",
                "v1/dlq");
        for (String path : lists) {
            var page = api.get(path, other.userToken).expect(200).path("items");
            for (var item : page) {
                String data = item.toString();
                for (String id : List.of(
                        ownerUserId,
                        owner.apiKeyId,
                        secretName,
                        owner.caName,
                        endpoint,
                        job,
                        workflow,
                        run,
                        workflowRun,
                        dlq)) ScenarioEnvironment.require(!data.contains(id), path + " listed another team's ID");
            }
        }
        return Map.of(
                "ownerTeam", owner.teamId, "otherTeam", other.teamId, "checkedRoutes", probes.size() + lists.size());
    }

    private record Probe(String method, String path, Object body, Map<String, String> headers) {}

    private Map<String, Object> autoProtection() throws Exception {
        ScenarioEnvironment team = ScenarioEnvironment.create(api, target, targetUrl);
        String endpointName = "scenario-protection-" + team.suffix;
        String endpoint = team.endpoint("protection", "/behave/client-error", Map.of());
        String recurring = team.createJob("auto-pause", endpointName, Map.of("type", "INTERVAL", "everySec", 1));
        ScenarioEnvironment.await(
                Duration.ofSeconds(45),
                () -> "AUTO_PAUSED"
                                .equals(api.get("v1/jobs/" + recurring, team.apiKey)
                                        .expect(200)
                                        .path("status")
                                        .asString())
                        ? true
                        : null);
        var deadRuns = api.get("v1/jobs/" + recurring + "/runs?status=DEAD_LETTERED", team.apiKey)
                .expect(200)
                .path("items");
        ScenarioEnvironment.require(deadRuns.size() >= 3, "Auto-pause occurred before three dead runs");
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (int index = 0; index < 50; index++)
            jobs.add(team.job("suspend-" + index, endpointName, Map.of("type", "IMMEDIATE")));
        var batch = api.post("v1/jobs:batch", Map.of("jobs", jobs), team.apiKey)
                .expect(207)
                .path("results");
        ScenarioEnvironment.require(batch.size() == 50, "Auto-suspend batch was incomplete");
        for (var item : batch)
            ScenarioEnvironment.require(item.path("status").asInt() == 201, "Auto-suspend job creation failed");
        ScenarioEnvironment.await(Duration.ofMinutes(2), () -> {
            var page = api.get("v1/dlq?status=OPEN&endpoint=" + endpointName + "&limit=200", team.apiKey)
                    .expect(200)
                    .path("items");
            return page.size() >= 50 ? true : null;
        });
        ScenarioEnvironment.await(Duration.ofMinutes(2), () -> {
            var current = api.get("v1/endpoints/" + endpoint, team.apiKey).expect(200);
            return "SUSPENDED".equals(current.path("status").asString())
                            && "AUTO_SUSPENDED"
                                    .equals(current.path("statusReason").asString())
                    ? true
                    : null;
        });
        return Map.of("autoPausedJob", recurring, "autoSuspendedEndpoint", endpoint, "deadLetteredJobs", 50);
    }
}
