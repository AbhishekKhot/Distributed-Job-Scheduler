package dev.kairos.executor.call.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.retry.StatusOverride;
import dev.kairos.domain.retry.StatusRules;
import dev.kairos.domain.run.Fence;
import dev.kairos.domain.template.OutputSpec;
import dev.kairos.engine.lock.LockToken;
import dev.kairos.engine.lock.RunLock;
import dev.kairos.executor.call.application.ResultPublisher;
import dev.kairos.executor.gate.application.ExecutionContext;
import dev.kairos.http.auth.AuthMaterial;
import dev.kairos.http.auth.Authenticator;
import dev.kairos.http.client.HttpCallOutcome;
import dev.kairos.http.client.OutboundHttpClient;
import dev.kairos.http.client.ResponseBody;
import dev.kairos.http.client.SendObserver;
import dev.kairos.http.redact.Redactor;
import dev.kairos.http.request.HttpMethod;
import dev.kairos.http.request.OutboundRequest;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.CallOutcome;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.Result;
import dev.kairos.testsupport.TestIds;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/** Exercises classification, output sealing and the durable result envelope. */
class ExecutorExecutionActionTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final String SENSITIVE = "workflow-secret-6f3c";
    private int publishFailures;
    private int publishCalls;

    @Test
    void successfulResponsePublishesExtractedOutputAndSealsSensitiveOutput() throws Exception {
        Result result = run(StatusRules.DEFAULT, response(201, "{\"id\":\"ord-17\",\"secret\":\"" + SENSITIVE + "\"}"));

        assertThat(result.getOutcome()).isEqualTo(CallOutcome.CALL_OUTCOME_SUCCEEDED);
        assertThat(result.getOutputsMap()).containsKey("id").doesNotContainKey("secret");
        assertThat(result.getSealedOutputs().toByteArray()).containsExactly(new byte[] {1, 2, 3, 4});
        assertThat(result.getResponseSnippet()).doesNotContain(SENSITIVE);
        assertThat(new String(result.toByteArray(), StandardCharsets.ISO_8859_1))
                .doesNotContain(SENSITIVE);
    }

    @Test
    void fiveHundredIsClassifiedAsRetryableHttpFailure() throws Exception {
        Result result = run(StatusRules.DEFAULT, response(500, "{\"message\":\"failed\"}"));

        assertThat(result.getOutcome()).isEqualTo(CallOutcome.CALL_OUTCOME_FAILED);
        assertThat(result.getErrorClass()).isEqualTo(dev.kairos.proto.v1.ErrorClass.ERROR_CLASS_HTTP_5XX);
        assertThat(result.getHttpStatus()).isEqualTo(500);
    }

    @Test
    void configuredConflictOverrideIsSuccessful() throws Exception {
        StatusRules rules = StatusRules.parse(List.of("2xx"), Map.of("409", StatusOverride.SUCCESS));
        Result result = run(rules, response(409, "{\"id\":\"ord-existing\",\"secret\":\"" + SENSITIVE + "\"}"));

        assertThat(result.getOutcome()).isEqualTo(CallOutcome.CALL_OUTCOME_SUCCEEDED);
        assertThat(result.getHttpStatus()).isEqualTo(409);
    }

    @Test
    void timeoutAfterSendIsUnknownOutcome() throws Exception {
        HttpCallOutcome timeout = new HttpCallOutcome.Failed(
                new dev.kairos.domain.retry.CallResult.TimedOut(true), "request timed out", NOW, NOW.plusSeconds(2));
        Result result = run(timeout);

        assertThat(result.getOutcome()).isEqualTo(CallOutcome.CALL_OUTCOME_UNKNOWN);
        assertThat(result.getErrorClass()).isEqualTo(dev.kairos.proto.v1.ErrorClass.ERROR_CLASS_TIMEOUT);
        assertThat(result.getSentMarker()).isTrue();
    }

    @Test
    void sendVetoDefersWithoutMarkingTheRequestSent() throws Exception {
        Result result = run(new HttpCallOutcome.NotSent("lease is too short", NOW, NOW.plusMillis(1)));

        assertThat(result.getOutcome()).isEqualTo(CallOutcome.CALL_OUTCOME_DEFERRED);
        assertThat(result.getSentMarker()).isFalse();
        assertThat(result.getRetryAfterMs()).isPositive();
    }

    @Test
    void renderingFailurePublishesRenderFailedResultWithoutCallingTheTarget() throws Exception {
        Result result = runWithRules(StatusRules.DEFAULT, response(200, "{}"), true);

        assertThat(result.getOutcome()).isEqualTo(CallOutcome.CALL_OUTCOME_FAILED);
        assertThat(result.getErrorClass()).isEqualTo(dev.kairos.proto.v1.ErrorClass.ERROR_CLASS_RENDER_FAILED);
        assertThat(result.getSentMarker()).isFalse();
    }

    @Test
    void retriesKafkaPublicationUntilAcknowledged() throws Exception {
        publishFailures = 2;

        run(StatusRules.DEFAULT, response(201, "{\"id\":\"ord-17\",\"secret\":\"" + SENSITIVE + "\"}"));

        assertThat(publishCalls).isEqualTo(3);
    }

    private Result run(StatusRules rules, HttpCallOutcome outcome) throws Exception {
        return runWithRules(rules, outcome);
    }

    private Result run(HttpCallOutcome outcome) throws Exception {
        return runWithRules(StatusRules.DEFAULT, outcome);
    }

    private Result runWithRules(StatusRules rules, HttpCallOutcome outcome) throws Exception {
        return runWithRules(rules, outcome, false);
    }

    private Result runWithRules(StatusRules rules, HttpCallOutcome outcome, boolean renderFailure) throws Exception {
        RunId runId = TestIds.runId(0);
        dev.kairos.domain.id.WorkflowRunId targetId = TestIds.workflowRunId(0);
        dev.kairos.domain.id.TeamId teamId = TestIds.teamId();
        dev.kairos.domain.id.EndpointId endpointId = TestIds.endpointId();
        Dispatch dispatch = Dispatch.newBuilder()
                .setRunId(ProtoIds.toBytes(runId.value()))
                .setTargetId(ProtoIds.toBytes(targetId.value()))
                .setTeamId(ProtoIds.toBytes(teamId.value()))
                .setEndpointId(ProtoIds.toBytes(endpointId.value()))
                .setEndpointVersion(1)
                .setAttempt(2)
                .setShard(0)
                .setTraceParent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .build();
        ExecutionContext context =
                new ExecutionContext(dispatch, runId, new Fence(2), Duration.ofSeconds(2), Duration.ofSeconds(12));
        context.acquired(
                new RunLock(runId, new LockToken(new Fence(2), "test-executor", "0123456789abcdef0123456789abcdef")));
        context.claimed(new Fence(3), NOW.plusSeconds(12));

        EndpointVersion endpoint = new EndpointVersion(
                "https://target.example.test/orders",
                HttpMethod.POST,
                Duration.ofSeconds(2),
                Map.of(),
                dev.kairos.http.auth.AuthType.NONE,
                null,
                null,
                null,
                null,
                null,
                null,
                rules,
                Map.of(
                        "id", new OutputSpec(OutputSpec.Source.BODY, "$.id", true, false),
                        "secret", new OutputSpec(OutputSpec.Source.BODY, "$.secret", true, true)),
                0);
        JdbcEndpointVersions endpoints = mock(JdbcEndpointVersions.class);
        when(endpoints.find(any(), any(), anyInt())).thenReturn(Optional.of(endpoint));
        CallRenderer renderer = mock(CallRenderer.class);
        OutboundHttpClient client = mock(OutboundHttpClient.class);
        OutboundRequest request = OutboundRequest.builder(
                        HttpMethod.POST, URI.create("https://target.example.test/orders"), Duration.ofSeconds(2))
                .body(new byte[] {1})
                .build();
        RenderedCall rendered = new RenderedCall(request, new AuthMaterial.None(), client, List.of());
        if (renderFailure) {
            when(renderer.render(dispatch, endpoint))
                    .thenThrow(new IllegalArgumentException("missing required parameter"));
        } else {
            when(renderer.render(dispatch, endpoint)).thenReturn(rendered);
        }
        when(client.execute(any(), any(SendObserver.class))).thenReturn(outcome);
        JdbcExecutionSecrets secrets = mock(JdbcExecutionSecrets.class);
        when(secrets.sealOutputs(any(), any(), anyMap())).thenReturn(new byte[] {1, 2, 3, 4});
        List<Result> published = new java.util.ArrayList<>();
        List<UUID> publishedKeys = new java.util.ArrayList<>();
        List<String> publishedTraces = new java.util.ArrayList<>();
        ResultPublisher publisher = (message, key, trace) -> {
            publishCalls++;
            if (publishCalls <= publishFailures) throw new java.io.IOException("temporary Kafka failure");
            published.add(message);
            publishedKeys.add(key);
            publishedTraces.add(trace);
        };
        ExecutorExecutionAction action = new ExecutorExecutionAction(
                endpoints,
                renderer,
                secrets,
                new EndpointProtections(new SimpleMeterRegistry()),
                publisher,
                mock(Authenticator.class),
                new Redactor(List.of()),
                JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry(),
                new MockEnvironment().withProperty("HOSTNAME", "test-executor"));

        action.execute(context, SendObserver.NONE);
        assertThat(published).hasSize(1);
        assertThat(publishedKeys).containsExactly(targetId.value());
        assertThat(publishedTraces).containsExactly(dispatch.getTraceParent());
        assertThat(ProtoIds.toUuid(published.getFirst().getRunId())).isEqualTo(runId.value());
        assertThat(ProtoIds.toUuid(dispatch.getTargetId())).isEqualTo(targetId.value());
        return published.getFirst();
    }

    private static HttpCallOutcome response(int status, String body) throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new HttpCallOutcome.Received(
                status,
                List.of(new dev.kairos.http.request.HeaderField("Content-Type", "application/json")),
                ResponseBody.of(bytes, json.readTree(bytes)),
                NOW,
                NOW.plusMillis(90));
    }
}
