package dev.kairos.executor.call.infrastructure;

import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.KairosIdFactory;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.retry.CallResult;
import dev.kairos.domain.retry.Classification;
import dev.kairos.domain.retry.OutcomeClassifier;
import dev.kairos.domain.template.JsonValue;
import dev.kairos.domain.template.OutputExtractor;
import dev.kairos.domain.template.OutputValue;
import dev.kairos.http.client.HttpCallOutcome;
import dev.kairos.http.client.SendObserver;
import dev.kairos.http.redact.Redactor;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.NameValue;
import dev.kairos.proto.v1.RequestSnapshot;
import dev.kairos.security.token.AccessTokenClaims;
import dev.kairos.security.token.AccessTokenService;
import dev.kairos.security.token.TokenVerification;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Authenticated internal endpoint test path; it executes once and never publishes or stores a result. */
@RestController
@RequestMapping("/internal/v1/endpoint-tests")
public final class InternalEndpointTestController {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int TESTS_PER_WINDOW = 10;

    private final JdbcEndpointVersions endpoints;
    private final CallRenderer renderer;
    private final AccessTokenService tokens;
    private final Redactor redactor;
    private final JsonMapper json;
    private final Clock clock;
    private final KairosIdFactory ids;
    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();

    /** Creates the internal endpoint test handler. */
    public InternalEndpointTestController(
            JdbcEndpointVersions endpoints,
            CallRenderer renderer,
            AccessTokenService tokens,
            Redactor redactor,
            JsonMapper json,
            Clock clock,
            KairosIdFactory ids) {
        this.endpoints = endpoints;
        this.renderer = renderer;
        this.tokens = tokens;
        this.redactor = redactor;
        this.json = json;
        this.clock = clock;
        this.ids = ids;
    }

    /** Runs one target call with the same renderer and HTTP client used by normal execution. */
    @PostMapping
    public TestResponse test(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody TestRequest request) {
        AccessTokenClaims claims = claims(authorization);
        TeamId team = claims.teamId().orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (!admit(team.value()))
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Endpoint tests are limited to 10 per minute.");
        EndpointId endpointId;
        try {
            endpointId = new EndpointId(UUID.fromString(request.endpointId()));
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The endpoint id is invalid.");
        }
        int version = endpoints
                .currentVersion(team, endpointId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The endpoint does not exist."));
        EndpointVersion endpoint = endpoints
                .find(team, endpointId, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "The endpoint does not exist."));
        UUID run = ids.newId();
        Dispatch dispatch = dispatch(team, endpointId, run, version, request);
        try {
            RenderedCall rendered = renderer.render(dispatch, endpoint);
            HttpCallOutcome outcome = rendered.client().execute(rendered.request(), SendObserver.NONE);
            return response(endpoint, rendered, outcome);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The request could not be rendered safely.");
        } catch (ResponseStatusException rejected) {
            throw rejected;
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The endpoint test could not be completed.");
        }
    }

    private AccessTokenClaims claims(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (!(tokens.verify(authorization.substring(7)) instanceof TokenVerification.Valid valid)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        AccessTokenClaims claims = valid.claims();
        if (Duration.between(claims.issuedAt(), claims.expiresAt()).compareTo(Duration.ofSeconds(30)) > 0
                || claims.expiresAt().isBefore(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return claims;
    }

    private boolean admit(UUID team) {
        long now = clock.millis();
        Window window = windows.compute(
                team,
                (ignored, previous) -> previous == null || now - previous.startedAt() >= WINDOW.toMillis()
                        ? new Window(now, 1)
                        : new Window(previous.startedAt(), previous.count() + 1));
        return window.count() <= TESTS_PER_WINDOW;
    }

    private Dispatch dispatch(TeamId team, EndpointId endpointId, UUID run, int version, TestRequest request) {
        RequestSnapshot.Builder snapshot = RequestSnapshot.newBuilder();
        if (request.pathParams() != null) snapshot.putAllPathParams(request.pathParams());
        if (request.query() != null)
            request.query()
                    .forEach((key, value) -> snapshot.addQuery(
                            NameValue.newBuilder().setName(key).setValue(value).build()));
        if (request.headers() != null)
            request.headers()
                    .forEach((key, value) -> snapshot.addHeaders(
                            NameValue.newBuilder().setName(key).setValue(value).build()));
        if (request.body() != null) {
            try {
                snapshot.setBody(com.google.protobuf.ByteString.copyFrom(json.writeValueAsBytes(request.body())));
            } catch (Exception invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The request body is invalid.");
            }
        }
        return Dispatch.newBuilder()
                .setRunId(ProtoIds.toBytes(run))
                .setTargetId(ProtoIds.toBytes(run))
                .setTeamId(ProtoIds.toBytes(team.value()))
                .setEndpointId(ProtoIds.toBytes(endpointId.value()))
                .setEndpointVersion(version)
                .setAttempt(1)
                .setRequest(snapshot)
                .build();
    }

    private TestResponse response(EndpointVersion endpoint, RenderedCall rendered, HttpCallOutcome outcome) {
        int status = outcome instanceof HttpCallOutcome.Received received
                ? received.status()
                : outcome instanceof HttpCallOutcome.TooLarge large ? large.status() : 0;
        CallResult result = outcome.callResult().orElseGet(() -> new CallResult.ConnectionFailed(false));
        Classification classification = OutcomeClassifier.classify(result, endpoint.statusRules());
        Map<String, Object> outputs = new LinkedHashMap<>();
        if (outcome instanceof HttpCallOutcome.Received received
                && received.body().json().isPresent()) {
            try {
                JsonValue body = JsonValue.of(json.readValue(received.body().bytes(), Object.class));
                Map<String, java.util.List<String>> headers = new LinkedHashMap<>();
                received.headers()
                        .forEach(
                                header -> headers.computeIfAbsent(header.name(), ignored -> new java.util.ArrayList<>())
                                        .add(header.value()));
                Map<String, OutputValue> extracted = OutputExtractor.extract(endpoint.outputs(), body, headers, status);
                extracted.forEach((name, value) -> {
                    if (!value.sensitive()) {
                        String safeValue = redactor.redactError(value.value().json(), rendered.secrets());
                        try {
                            outputs.put(name, json.readValue(safeValue, Object.class));
                        } catch (Exception ignored) {
                            outputs.put(name, safeValue);
                        }
                    } else {
                        outputs.put(name, "<redacted>");
                    }
                });
            } catch (RuntimeException ignored) {
                outputs.clear();
            }
        }
        boolean success = classification instanceof Classification.Success;
        String outcomeName = success ? "SUCCESS" : "FAILURE";
        String error = outcome instanceof HttpCallOutcome.Failed failed
                ? redactor.redactError(failed.detail(), rendered.secrets())
                : success || status == 0
                        ? ""
                        : redactor.redactError("The endpoint returned HTTP " + status + ".", rendered.secrets());
        return new TestResponse(
                status,
                Duration.between(outcome.startedAt(), outcome.finishedAt()).toMillis(),
                outcomeName,
                Map.copyOf(outputs),
                error);
    }

    /** Request data accepted from kairos-api; endpoint id is required and fields are optional. */
    public record TestRequest(
            String endpointId,
            Map<String, String> pathParams,
            Map<String, String> query,
            Map<String, String> headers,
            Object body) {}

    /** Redacted endpoint test response. */
    public record TestResponse(
            int httpStatus, long latencyMs, String outcome, Map<String, Object> outputs, String error) {}

    private record Window(long startedAt, int count) {}
}
