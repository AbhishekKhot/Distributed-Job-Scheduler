package dev.kairos.api.endpoints.application;

import dev.kairos.domain.id.TeamId;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Calls the executor's private, authenticated endpoint-test route. */
public interface EndpointTestGateway {
    Result test(TeamId team, UUID endpoint, Request request);

    record Request(
            Map<String, String> pathParams,
            Map<String, String> query,
            Map<String, String> headers,
            @Nullable Object body) {}

    record Result(int httpStatus, long latencyMs, String outcome, Map<String, Object> outputs, String error) {}
}
