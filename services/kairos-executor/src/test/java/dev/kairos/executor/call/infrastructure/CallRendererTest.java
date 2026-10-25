package dev.kairos.executor.call.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.kairos.domain.id.RunId;
import dev.kairos.domain.retry.StatusRules;
import dev.kairos.domain.template.OutputSpec;
import dev.kairos.http.auth.AuthMaterial;
import dev.kairos.http.auth.Authenticator;
import dev.kairos.http.auth.OAuth2TokenProvider;
import dev.kairos.http.client.HttpClientFactory;
import dev.kairos.http.client.OutboundHttpClient;
import dev.kairos.http.request.HttpMethod;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.NameValue;
import dev.kairos.proto.v1.RequestSnapshot;
import dev.kairos.security.keyset.KeysetService;
import dev.kairos.security.keyset.LocalKeyEncryptionKey;
import dev.kairos.security.signing.RequestSigner;
import dev.kairos.testsupport.TestIds;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Verifies encoded URL components, headers, body, auth and signature construction. */
class CallRendererTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC);
    private static final String LOCAL_KEK = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void preservesEncodedPathAndQueryAndAddsExecutionHeadersAndSignature() throws Exception {
        assertRenderedCall(false);
    }

    @Test
    void restoresPinnedWorkflowRequestsAndPreservesEndpointDefaultHeaders() throws Exception {
        assertRenderedCall(true);
    }

    private void assertRenderedCall(boolean pinned) throws Exception {
        var team = TestIds.teamId();
        var run = TestIds.runId(0);
        var workflowRun = TestIds.workflowRunId(0);
        var endpointId = TestIds.endpointId();
        JsonMapper json = JsonMapper.builder().build();
        RequestSnapshot snapshot = RequestSnapshot.newBuilder()
                .putPathParams("orderId", "a/b")
                .addQuery(NameValue.newBuilder().setName("search+term").setValue("x+y"))
                .addHeaders(NameValue.newBuilder().setName("X-Mode").setValue("snapshot"))
                .setBody(com.google.protobuf.ByteString.copyFrom(json.writeValueAsBytes(Map.of("quantity", 2))))
                .build();
        Dispatch dispatch = Dispatch.newBuilder()
                .setRunId(ProtoIds.toBytes(run.value()))
                .setTargetId(ProtoIds.toBytes(workflowRun.value()))
                .setTeamId(ProtoIds.toBytes(team.value()))
                .setEndpointId(ProtoIds.toBytes(endpointId.value()))
                .setEndpointVersion(3)
                .setAttempt(4)
                .setTraceParent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .setRequest(pinned ? RequestSnapshot.getDefaultInstance() : snapshot)
                .build();
        EndpointVersion endpoint = endpoint("https://target.example.test/orders/{orderId}?fixed=a%2Fb");
        JdbcExecutionSecrets secrets = mock(JdbcExecutionSecrets.class);
        when(secrets.workflowRequest(dispatch))
                .thenReturn(
                        pinned
                                ? java.util.Optional.of(new dev.kairos.domain.template.RequestTemplate(
                                        Map.of("orderId", "a/b"),
                                        Map.of("search+term", "x+y"),
                                        Map.of("X-Mode", "snapshot"),
                                        dev.kairos.domain.template.JsonValue.of(Map.of("quantity", 2))))
                                : java.util.Optional.empty());
        when(secrets.inputs(
                        team,
                        new RunId(workflowRun.value()),
                        snapshot.getSealedInputs().toByteArray()))
                .thenReturn(Map.of());
        when(secrets.auth(team, endpointId, 3, endpoint)).thenReturn(new AuthMaterial.None());
        KeysetService keysets = new KeysetService(
                LocalKeyEncryptionKey.forLocalProfile(LOCAL_KEK, true),
                new SecureRandom(),
                Duration.ofMinutes(10),
                100);
        when(secrets.signingKey(team))
                .thenReturn(
                        keysets.signingKey(team, keysets.createSigningKey(team).sealed()));
        OutboundHttpClient client = mock(OutboundHttpClient.class);
        HttpClientFactory clients = mock(HttpClientFactory.class);
        when(clients.defaultClient()).thenReturn(client);
        CallRenderer renderer = new CallRenderer(
                secrets,
                clients,
                new RequestSigner(CLOCK),
                json,
                new Authenticator(new OAuth2TokenProvider(CLOCK, 10)));

        RenderedCall result = renderer.render(dispatch, endpoint);

        assertThat(result.request().url().getRawPath()).isEqualTo("/orders/a%2Fb");
        assertThat(result.request().url().getRawQuery()).isEqualTo("fixed=a%2Fb&search%2Bterm=x%2By");
        assertThat(result.request().header("X-Mode")).contains("snapshot");
        assertThat(result.request().header("X-Default")).contains("present");
        assertThat(result.request().header("Idempotency-Key")).contains(run.toString());
        assertThat(result.request().header("X-Kairos-Run-Id")).contains(run.toString());
        assertThat(result.request().header("X-Kairos-Attempt")).contains("4");
        assertThat(result.request().header("traceparent")).contains(dispatch.getTraceParent());
        assertThat(result.request().header(RequestSigner.TIMESTAMP_HEADER)).contains("1792843200");
        assertThat(result.request().header(RequestSigner.SIGNATURE_HEADER))
                .hasValueSatisfying(value -> assertThat(value).startsWith("v1=").hasSize(67));
        assertThat(result.request().body()).containsExactly(json.writeValueAsBytes(Map.of("quantity", 2)));
    }

    @Test
    void rejectsMissingPathValuesBeforeCallingTheHttpClient() {
        var team = TestIds.teamId();
        var run = TestIds.runId(0);
        var workflowRun = TestIds.workflowRunId(0);
        var endpointId = TestIds.endpointId();
        Dispatch dispatch = Dispatch.newBuilder()
                .setRunId(ProtoIds.toBytes(run.value()))
                .setTargetId(ProtoIds.toBytes(workflowRun.value()))
                .setTeamId(ProtoIds.toBytes(team.value()))
                .setEndpointId(ProtoIds.toBytes(endpointId.value()))
                .setEndpointVersion(1)
                .setRequest(RequestSnapshot.getDefaultInstance())
                .build();
        JdbcExecutionSecrets secrets = mock(JdbcExecutionSecrets.class);
        when(secrets.inputs(any(), any(), any())).thenReturn(Map.of());
        when(secrets.auth(any(), any(), anyInt(), any())).thenReturn(new AuthMaterial.None());
        HttpClientFactory clients = mock(HttpClientFactory.class);
        CallRenderer renderer = new CallRenderer(
                secrets,
                clients,
                new RequestSigner(CLOCK),
                JsonMapper.builder().build(),
                new Authenticator(new OAuth2TokenProvider(CLOCK, 10)));

        assertThatThrownBy(() -> renderer.render(dispatch, endpoint("https://target.example.test/orders/{orderId}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A required path parameter is missing");
    }

    private static EndpointVersion endpoint(String url) {
        return new EndpointVersion(
                url,
                HttpMethod.POST,
                Duration.ofSeconds(8),
                Map.of("X-Mode", "default", "X-Default", "present"),
                dev.kairos.http.auth.AuthType.NONE,
                null,
                null,
                null,
                null,
                null,
                null,
                StatusRules.DEFAULT,
                Map.of("orderId", new OutputSpec(OutputSpec.Source.BODY, "$.id", true, false)),
                0);
    }
}
