package dev.kairos.api.endpoints.web;

import dev.kairos.api.endpoints.application.EndpointSpec;
import dev.kairos.api.endpoints.application.EndpointStore;
import dev.kairos.api.endpoints.application.EndpointsUseCase;
import dev.kairos.api.security.TeamContext;
import dev.kairos.api.support.etag.ETags;
import dev.kairos.api.support.etag.IfMatch;
import dev.kairos.api.support.paging.CursorCodec;
import dev.kairos.api.support.paging.Page;
import dev.kairos.api.support.paging.PageLimit;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoint registry APIs 25–29. */
@RestController
public class EndpointsController {
    private final EndpointsUseCase endpoints;
    private final CursorCodec cursors;

    public EndpointsController(EndpointsUseCase endpoints, CursorCodec cursors) {
        this.endpoints = endpoints;
        this.cursors = cursors;
    }

    @PostMapping(path = "/v1/endpoints", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EndpointResponse> create(TeamContext team, @RequestBody EndpointRequest request) {
        EndpointStore.Endpoint created = endpoints.create(team.teamId(), team.actor(), request.name(), request.spec());
        return ResponseEntity.created(URI.create("/v1/endpoints/" + created.id()))
                .header(HttpHeaders.ETAG, ETags.of(created.version()))
                .body(EndpointResponse.of(created));
    }

    @GetMapping(path = "/v1/endpoints", produces = MediaType.APPLICATION_JSON_VALUE)
    public Page<EndpointResponse> list(
            TeamContext team,
            @RequestParam(name = "status", required = false) @Nullable String status,
            @RequestParam(name = "limit", required = false) @Nullable Integer limit,
            @RequestParam(name = "cursor", required = false) @Nullable String cursor) {
        String context = "endpoints" + (status == null ? "" : ":" + status);
        EndpointStore.Key after = null;
        if (cursor != null) {
            List<String> values = cursors.decode(context, team.teamId(), cursor);
            if (values.size() != 2) throw ValidationFailedException.of("cursor", FieldError.FORMAT);
            try {
                after = new EndpointStore.Key(Instant.parse(values.getFirst()), UUID.fromString(values.get(1)));
            } catch (RuntimeException exception) {
                throw ValidationFailedException.of("cursor", FieldError.FORMAT);
            }
        }
        EndpointsUseCase.ListResult result = endpoints.list(team.teamId(), status, after, PageLimit.resolve(limit));
        String next = result.next() == null
                ? null
                : cursors.encode(
                        context,
                        team.teamId(),
                        List.of(
                                result.next().createdAt().toString(),
                                result.next().id().toString()));
        return new Page<>(result.items().stream().map(EndpointResponse::of).toList(), next);
    }

    @GetMapping(path = "/v1/endpoints/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EndpointResponse> get(TeamContext team, @PathVariable UUID id) {
        EndpointStore.Endpoint endpoint = endpoints.get(team.teamId(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.of(endpoint.version()))
                .body(EndpointResponse.of(endpoint));
    }

    @PutMapping(path = "/v1/endpoints/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EndpointResponse> update(
            TeamContext team,
            @PathVariable UUID id,
            @IfMatch long expectedVersion,
            @RequestBody EndpointRequest request) {
        EndpointStore.Endpoint updated =
                endpoints.update(team.teamId(), team.actor(), id, request.name(), expectedVersion, request.spec());
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.of(updated.version()))
                .body(EndpointResponse.of(updated));
    }

    @DeleteMapping("/v1/endpoints/{id}")
    public ResponseEntity<Void> delete(TeamContext team, @PathVariable UUID id) {
        endpoints.delete(team.teamId(), team.actor(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/endpoints/{id}/suspend")
    public SuspendResponse suspend(
            TeamContext team, @PathVariable UUID id, @RequestBody(required = false) @Nullable SuspendRequest body) {
        EndpointStore.Endpoint endpoint =
                endpoints.suspend(team.teamId(), team.actor(), id, body == null ? null : body.reason());
        return SuspendResponse.of(endpoint);
    }

    @PostMapping("/v1/endpoints/{id}/resume")
    public ResumeResponse resume(TeamContext team, @PathVariable UUID id) {
        return ResumeResponse.of(endpoints.resume(team.teamId(), team.actor(), id));
    }

    @PostMapping(path = "/v1/endpoints/{id}:test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public EndpointTestResponse test(
            TeamContext team, @PathVariable UUID id, @RequestBody EndpointTestRequest request) {
        var result = endpoints.test(
                team.teamId(),
                id,
                new dev.kairos.api.endpoints.application.EndpointTestGateway.Request(
                        request.pathParams() == null ? Map.of() : request.pathParams(),
                        request.query() == null ? Map.of() : request.query(),
                        request.headers() == null ? Map.of() : request.headers(),
                        request.body()));
        return new EndpointTestResponse(
                result.httpStatus(), result.latencyMs(), result.outcome(), result.outputs(), result.error());
    }

    @GetMapping(path = "/v1/endpoints/{id}/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public dev.kairos.api.endpoints.application.EndpointHealthReader.EndpointHealth health(
            TeamContext team,
            @PathVariable UUID id,
            @RequestParam(name = "window", required = false) @Nullable String window) {
        return endpoints.health(team.teamId(), id, window);
    }

    public record EndpointTestRequest(
            @Nullable Map<String, String> pathParams,
            @Nullable Map<String, String> query,
            @Nullable Map<String, String> headers,
            @Nullable Object body) {}

    public record EndpointTestResponse(
            int httpStatus, long latencyMs, String outcome, Map<String, Object> outputs, String error) {}

    public record SuspendRequest(@Nullable String reason) {}

    public record SuspendResponse(
            UUID id,
            String name,
            String method,
            String status,
            long version,
            Instant updatedAt,
            @Nullable String statusReason) {
        static SuspendResponse of(EndpointStore.Endpoint endpoint) {
            return new SuspendResponse(
                    endpoint.id(),
                    endpoint.name(),
                    endpoint.spec().method(),
                    endpoint.status(),
                    endpoint.version(),
                    endpoint.updatedAt(),
                    endpoint.statusReason());
        }
    }

    public record ResumeResponse(UUID id, String name, String method, String status, long version, Instant updatedAt) {
        static ResumeResponse of(EndpointStore.Endpoint endpoint) {
            return new ResumeResponse(
                    endpoint.id(),
                    endpoint.name(),
                    endpoint.spec().method(),
                    endpoint.status(),
                    endpoint.version(),
                    endpoint.updatedAt());
        }
    }

    public record EndpointRequest(
            String name,
            String method,
            String urlTemplate,
            java.util.Map<String, String> defaultHeaders,
            EndpointSpec.Auth auth,
            @Nullable String caBundle,
            int timeoutMs,
            java.util.Set<String> successStatus,
            java.util.Map<String, String> statusOverrides,
            EndpointSpec.RetryPolicy retryPolicy,
            EndpointSpec.Limits limits,
            java.util.Map<String, EndpointSpec.Output> outputs,
            EndpointSpec.UnknownOutcomePolicy unknownOutcomePolicy,
            EndpointSpec.SuspendPolicy suspendPolicy) {
        public EndpointRequest {
            if (name == null) throw ValidationFailedException.of("name", FieldError.REQUIRED);
            if (method == null) throw ValidationFailedException.of("method", FieldError.REQUIRED);
            if (urlTemplate == null) throw ValidationFailedException.of("urlTemplate", FieldError.REQUIRED);
            if (retryPolicy == null) throw ValidationFailedException.of("retryPolicy", FieldError.REQUIRED);
            if (limits == null) throw ValidationFailedException.of("limits", FieldError.REQUIRED);
            defaultHeaders = defaultHeaders == null ? java.util.Map.of() : defaultHeaders;
            auth = auth == null
                    ? new EndpointSpec.Auth(EndpointSpec.AuthType.NONE, null, null, null, null, null)
                    : auth;
            timeoutMs = timeoutMs == 0 ? 30000 : timeoutMs;
            successStatus = successStatus == null ? java.util.Set.of("2xx") : successStatus;
            statusOverrides = statusOverrides == null ? java.util.Map.of() : statusOverrides;
            outputs = outputs == null ? java.util.Map.of() : outputs;
            unknownOutcomePolicy = unknownOutcomePolicy == null
                    ? EndpointSpec.UnknownOutcomePolicy.RETRY_WITH_IDEMPOTENCY_KEY
                    : unknownOutcomePolicy;
            suspendPolicy = suspendPolicy == null ? new EndpointSpec.SuspendPolicy(true) : suspendPolicy;
        }

        public EndpointSpec spec() {
            return new EndpointSpec(
                    method,
                    urlTemplate,
                    defaultHeaders,
                    auth,
                    caBundle,
                    timeoutMs,
                    successStatus,
                    statusOverrides,
                    retryPolicy,
                    limits,
                    outputs,
                    unknownOutcomePolicy,
                    suspendPolicy);
        }
    }

    public record EndpointResponse(
            UUID id,
            String name,
            String method,
            String urlTemplate,
            java.util.Map<String, String> defaultHeaders,
            EndpointSpec.Auth auth,
            @Nullable String caBundle,
            int timeoutMs,
            java.util.Set<String> successStatus,
            java.util.Map<String, String> statusOverrides,
            EndpointSpec.RetryPolicy retryPolicy,
            EndpointSpec.Limits limits,
            java.util.Map<String, EndpointSpec.Output> outputs,
            EndpointSpec.UnknownOutcomePolicy unknownOutcomePolicy,
            EndpointSpec.SuspendPolicy suspendPolicy,
            String status,
            @Nullable String statusReason,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        public static EndpointResponse of(EndpointStore.Endpoint endpoint) {
            EndpointSpec spec = endpoint.spec();
            return new EndpointResponse(
                    endpoint.id(),
                    endpoint.name(),
                    spec.method(),
                    spec.urlTemplate(),
                    spec.defaultHeaders(),
                    spec.auth(),
                    spec.caBundle(),
                    spec.timeoutMs(),
                    spec.successStatus(),
                    spec.statusOverrides(),
                    spec.retryPolicy(),
                    spec.limits(),
                    spec.outputs(),
                    spec.unknownOutcomePolicy(),
                    spec.suspendPolicy(),
                    endpoint.status(),
                    endpoint.statusReason(),
                    endpoint.version(),
                    endpoint.createdAt(),
                    endpoint.updatedAt());
        }
    }
}
