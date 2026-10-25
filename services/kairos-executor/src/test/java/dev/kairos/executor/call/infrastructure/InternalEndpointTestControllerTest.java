package dev.kairos.executor.call.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.retry.StatusRules;
import dev.kairos.domain.template.OutputSpec;
import dev.kairos.http.auth.AuthMaterial;
import dev.kairos.http.client.HttpCallOutcome;
import dev.kairos.http.client.OutboundHttpClient;
import dev.kairos.http.client.ResponseBody;
import dev.kairos.http.redact.Redactor;
import dev.kairos.http.request.HttpMethod;
import dev.kairos.http.request.OutboundRequest;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.security.token.AccessTokenClaims;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.security.token.TokenVerification;
import dev.kairos.testsupport.TestIds;
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
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Ensures internal tests require a short-lived service token and apply their per-team safety cap. */
class InternalEndpointTestControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    void rejectsMissingOrInvalidBearerTokens() {
        AccessTokenService tokens = mock(AccessTokenService.class);
        when(tokens.verify("invalid")).thenReturn(new TokenVerification.Invalid(TokenVerification.Reason.SIGNATURE));
        InternalEndpointTestController controller = controller(mock(JdbcEndpointVersions.class), tokens);

        assertThatThrownBy(() -> controller.test(null, request()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(failure ->
                        ((ResponseStatusException) failure).getStatusCode().value())
                .isEqualTo(401);
        assertThatThrownBy(() -> controller.test("Bearer invalid", request()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(failure ->
                        ((ResponseStatusException) failure).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    void enforcesTenTestCallsPerTeamPerProcess() {
        TeamId team = TestIds.teamId();
        EndpointId endpoint = TestIds.endpointId();
        AccessTokenClaims claims =
                new AccessTokenClaims(TestIds.userId(), team, UUID.randomUUID(), "token-id", NOW, NOW.plusSeconds(30));
        AccessTokenService tokens = mock(AccessTokenService.class);
        when(tokens.verify(anyString())).thenReturn(new TokenVerification.Valid(claims));
        JdbcEndpointVersions endpoints = mock(JdbcEndpointVersions.class);
        when(endpoints.currentVersion(team, endpoint)).thenReturn(Optional.empty());
        InternalEndpointTestController controller = controller(endpoints, tokens);
        InternalEndpointTestController.TestRequest request =
                new InternalEndpointTestController.TestRequest(endpoint.toString(), Map.of(), Map.of(), Map.of(), null);

        for (int call = 0; call < 10; call++) {
            assertThatThrownBy(() -> controller.test("Bearer signed-service-token", request))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(failure ->
                            ((ResponseStatusException) failure).getStatusCode().value())
                    .isEqualTo(404);
        }
        assertThatThrownBy(() -> controller.test("Bearer signed-service-token", request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(failure ->
                        ((ResponseStatusException) failure).getStatusCode().value())
                .isEqualTo(429);
    }

    @Test
    void executesOneTestAndRedactsSensitiveOutputs() throws Exception {
        TeamId team = TestIds.teamId();
        EndpointId endpointId = TestIds.endpointId();
        AccessTokenClaims claims =
                new AccessTokenClaims(TestIds.userId(), team, UUID.randomUUID(), "token-id", NOW, NOW.plusSeconds(30));
        AccessTokenService tokens = mock(AccessTokenService.class);
        when(tokens.verify("signed-service-token")).thenReturn(new TokenVerification.Valid(claims));
        EndpointVersion endpoint = new EndpointVersion(
                "https://target.example.test/orders",
                HttpMethod.POST,
                Duration.ofSeconds(3),
                Map.of(),
                dev.kairos.http.auth.AuthType.NONE,
                null,
                null,
                null,
                null,
                null,
                null,
                StatusRules.DEFAULT,
                Map.of(
                        "orderId", new OutputSpec(OutputSpec.Source.BODY, "$.id", true, false),
                        "secret", new OutputSpec(OutputSpec.Source.BODY, "$.secret", true, true)),
                0);
        JdbcEndpointVersions endpoints = mock(JdbcEndpointVersions.class);
        when(endpoints.currentVersion(team, endpointId)).thenReturn(Optional.of(1));
        when(endpoints.find(team, endpointId, 1)).thenReturn(Optional.of(endpoint));
        CallRenderer renderer = mock(CallRenderer.class);
        OutboundHttpClient client = mock(OutboundHttpClient.class);
        OutboundRequest outbound = OutboundRequest.builder(
                        HttpMethod.POST, URI.create(endpoint.urlTemplate()), endpoint.timeout())
                .build();
        when(renderer.render(any(), any()))
                .thenReturn(new RenderedCall(outbound, new AuthMaterial.None(), client, List.of()));
        JsonMapper json = JsonMapper.builder().build();
        byte[] body = "{\"id\":\"ord-22\",\"secret\":\"private-value\"}".getBytes(StandardCharsets.UTF_8);
        when(client.execute(any(), any()))
                .thenReturn(new HttpCallOutcome.Received(
                        201,
                        List.of(new dev.kairos.http.request.HeaderField("Content-Type", "application/json")),
                        ResponseBody.of(body, json.readTree(body)),
                        NOW,
                        NOW.plusMillis(75)));
        InternalEndpointTestController controller = controller(endpoints, renderer, tokens, json);

        InternalEndpointTestController.TestResponse response = controller.test(
                "Bearer signed-service-token",
                new InternalEndpointTestController.TestRequest(
                        endpointId.toString(), Map.of(), Map.of(), Map.of(), null));

        assertThat(response.httpStatus()).isEqualTo(201);
        assertThat(response.outcome()).isEqualTo("SUCCESS");
        assertThat(response.outputs()).containsEntry("orderId", "ord-22").containsEntry("secret", "<redacted>");
        assertThat(response.error()).isEmpty();
        ArgumentCaptor<Dispatch> dispatch = ArgumentCaptor.forClass(Dispatch.class);
        verify(renderer).render(dispatch.capture(), eq(endpoint));
        RunId runId = new RunId(ProtoIds.toUuid(dispatch.getValue().getRunId()));
        assertThat(new RunId(ProtoIds.toUuid(dispatch.getValue().getTargetId())))
                .isEqualTo(runId);
    }

    private static InternalEndpointTestController controller(
            JdbcEndpointVersions endpoints, AccessTokenService tokens) {
        return controller(
                endpoints,
                mock(CallRenderer.class),
                tokens,
                JsonMapper.builder().build());
    }

    private static InternalEndpointTestController controller(
            JdbcEndpointVersions endpoints, CallRenderer renderer, AccessTokenService tokens, JsonMapper json) {
        return new InternalEndpointTestController(
                endpoints,
                renderer,
                tokens,
                new Redactor(java.util.List.of()),
                json,
                Clock.fixed(NOW, ZoneOffset.UTC),
                TestIds.factory(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static InternalEndpointTestController.TestRequest request() {
        return new InternalEndpointTestController.TestRequest(
                UUID.randomUUID().toString(), Map.of(), Map.of(), Map.of(), null);
    }
}
