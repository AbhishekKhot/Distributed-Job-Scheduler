package dev.kairos.api.endpoints.infrastructure;

import dev.kairos.api.endpoints.application.EndpointTestGateway;
import dev.kairos.domain.id.TeamId;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/** Private-network adapter for the executor's T-243 endpoint-test route. */
public final class ExecutorEndpointTestGateway implements EndpointTestGateway {
    private final RestClient client;
    private final EndpointTestTokenIssuer tokens;

    public ExecutorEndpointTestGateway(EndpointTestTokenIssuer tokens, String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.tokens = tokens;
    }

    @Override
    public Result test(TeamId team, UUID endpoint, Request request) {
        String token = tokens.issue(team);
        InternalRequest payload = new InternalRequest(
                endpoint.toString(), request.pathParams(), request.query(), request.headers(), request.body());
        try {
            InternalResponse response = client.post()
                    .uri("/internal/v1/endpoint-tests")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .body(payload)
                    .retrieve()
                    .body(InternalResponse.class);
            if (response == null) throw new IllegalStateException("executor returned an empty response");
            return new Result(
                    response.httpStatus(),
                    response.latencyMs(),
                    response.outcome(),
                    response.outputs(),
                    response.error());
        } catch (org.springframework.web.client.RestClientResponseException error) {
            if (error.getStatusCode().value() == 400) {
                throw new dev.kairos.domain.error.ValidationFailedException(
                        dev.kairos.domain.error.ErrorCode.RENDER_FAILED,
                        "The request could not be rendered safely.",
                        java.util.List.of());
            }
            if (error.getStatusCode().value() == 404) {
                throw new dev.kairos.domain.error.NotFoundException("The endpoint does not exist.");
            }
            if (error.getStatusCode().value() == 429) {
                throw dev.kairos.api.support.problem.ApiException.rateLimited(java.time.Duration.ofMinutes(1));
            }
            throw new dev.kairos.api.support.problem.ApiException(
                    dev.kairos.domain.error.ErrorCode.UNAVAILABLE, "The endpoint test could not be completed.", null);
        }
    }

    private record InternalRequest(
            String endpointId,
            Map<String, String> pathParams,
            Map<String, String> query,
            Map<String, String> headers,
            Object body) {}

    private record InternalResponse(
            int httpStatus, long latencyMs, String outcome, Map<String, Object> outputs, String error) {}
}
