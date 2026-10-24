package dev.kairos.executor.call.infrastructure;

import com.google.protobuf.ByteString;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import dev.kairos.domain.id.EndpointId;
import dev.kairos.domain.id.RunId;
import dev.kairos.domain.id.TeamId;
import dev.kairos.domain.model.ErrorClass;
import dev.kairos.domain.retry.CallResult;
import dev.kairos.domain.retry.Classification;
import dev.kairos.domain.retry.FailureKind;
import dev.kairos.domain.retry.OutcomeClassifier;
import dev.kairos.domain.template.JsonValue;
import dev.kairos.domain.template.OutputExtractionException;
import dev.kairos.domain.template.OutputExtractor;
import dev.kairos.domain.template.OutputValue;
import dev.kairos.executor.call.application.ResultPublisher;
import dev.kairos.executor.gate.application.ExecutionAction;
import dev.kairos.executor.gate.application.ExecutionContext;
import dev.kairos.http.auth.AuthFailedException;
import dev.kairos.http.auth.Authenticator;
import dev.kairos.http.client.HttpCallOutcome;
import dev.kairos.http.client.SendObserver;
import dev.kairos.http.redact.Redactor;
import dev.kairos.proto.ProtoIds;
import dev.kairos.proto.v1.CallOutcome;
import dev.kairos.proto.v1.Dispatch;
import dev.kairos.proto.v1.Result;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Renders, executes, classifies and durably publishes one dispatch result. */
@Component
public final class ExecutorExecutionAction implements ExecutionAction {

    private final JdbcEndpointVersions endpoints;
    private final CallRenderer renderer;
    private final JdbcExecutionSecrets secrets;
    private final EndpointProtections protections;
    private final ResultPublisher publisher;
    private final Authenticator authenticator;
    private final Redactor redactor;
    private final JsonMapper json;
    private final Clock clock;
    private final MeterRegistry meters;
    private final String executorId;

    /** Creates the dispatch call action. */
    public ExecutorExecutionAction(
            JdbcEndpointVersions endpoints,
            CallRenderer renderer,
            JdbcExecutionSecrets secrets,
            EndpointProtections protections,
            ResultPublisher publisher,
            Authenticator authenticator,
            Redactor redactor,
            JsonMapper json,
            Clock clock,
            MeterRegistry meters,
            org.springframework.core.env.Environment environment) {
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.protections = Objects.requireNonNull(protections, "protections");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.executorId = environment.getProperty("HOSTNAME", "executor");
    }

    @Override
    public void execute(ExecutionContext context, SendObserver sendObserver) throws Exception {
        Dispatch dispatch = context.dispatch();
        Instant processingStarted = clock.instant();
        TeamId team = new TeamId(ProtoIds.toUuid(dispatch.getTeamId()));
        EndpointId endpointId = new EndpointId(ProtoIds.toUuid(dispatch.getEndpointId()));
        RunId runId = context.runId();
        UUID targetId = ProtoIds.toUuid(dispatch.getTargetId());
        EndpointVersion endpoint;
        try {
            endpoint = endpoints
                    .find(team, endpointId, dispatch.getEndpointVersion())
                    .orElseThrow(() -> new IllegalArgumentException("The pinned endpoint version is unavailable"));
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            Result failed =
                    renderFailure(context, processingStarted, "The pinned endpoint configuration could not be loaded.");
            publish(failed, targetId, dispatch.getTraceParent());
            record(failed, dispatch, processingStarted);
            return;
        }

        EndpointProtections.Admission admission = protections.acquire(endpointId.value(), endpoint.ratePerSec());
        if (!admission.allowed()) {
            Result deferred = base(context, processingStarted, clock.instant())
                    .setOutcome(CallOutcome.CALL_OUTCOME_DEFERRED)
                    .setRetryAfterMs(admission.retryAfterMillis())
                    .build();
            protections.abandon(admission);
            publish(deferred, targetId, dispatch.getTraceParent());
            record(deferred, dispatch, processingStarted);
            return;
        }

        RenderedCall rendered;
        try {
            rendered = renderer.render(dispatch, endpoint);
        } catch (AuthFailedException authFailure) {
            Instant failedAt = clock.instant();
            HttpCallOutcome failed =
                    new HttpCallOutcome.Failed(authFailure.callResult(), authFailure.getMessage(), failedAt, failedAt);
            Classification classification =
                    OutcomeClassifier.classify(authFailure.callResult(), endpoint.statusRules());
            protections.complete(admission, failed, false);
            Result result = base(context, failedAt, failedAt)
                    .setOutcome(
                            classification instanceof Classification.Failure failure
                                            && failure.kind() == FailureKind.UNKNOWN_OUTCOME
                                    ? CallOutcome.CALL_OUTCOME_UNKNOWN
                                    : CallOutcome.CALL_OUTCOME_FAILED)
                    .setErrorClass(dev.kairos.proto.v1.ErrorClass.forNumber(
                            classification instanceof Classification.Failure failure
                                    ? failure.errorClass().code()
                                    : ErrorClass.RENDER_FAILED.code()))
                    .setError(redactor.redactError(authFailure.getMessage(), List.of()))
                    .setSentMarker(false)
                    .build();
            publish(result, targetId, dispatch.getTraceParent());
            record(result, dispatch, processingStarted);
            return;
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            protections.abandon(admission);
            Result failed = renderFailure(context, processingStarted, "The request could not be rendered safely.");
            publish(failed, targetId, dispatch.getTraceParent());
            record(failed, dispatch, processingStarted);
            return;
        }

        HttpCallOutcome observed;
        try {
            observed = rendered.client().execute(rendered.request(), sendObserver);
        } catch (AuthFailedException authFailure) {
            observed = new HttpCallOutcome.Failed(
                    authFailure.callResult(), authFailure.getMessage(), clock.instant(), clock.instant());
        }
        if (observed instanceof HttpCallOutcome.Received received && received.status() == 401) {
            authenticator.onUnauthorized(rendered.auth());
        }

        CallResult callResult = observed.callResult().orElse(null);
        Map<String, OutputValue> outputs = Map.of();
        if (observed instanceof HttpCallOutcome.Received received
                && OutcomeClassifier.classify(new CallResult.Responded(received.status()), endpoint.statusRules())
                        instanceof Classification.Success) {
            try {
                outputs = extract(endpoint, received);
            } catch (OutputExtractionException missing) {
                callResult = missing.errorClass() == ErrorClass.RESPONSE_TOO_LARGE
                        ? new CallResult.ResponseTooLarge(received.status())
                        : new CallResult.OutputMissing(received.status());
            }
        }

        Instant startedAt = observed.startedAt();
        Instant finishedAt = observed.finishedAt();
        if (observed instanceof HttpCallOutcome.NotSent notSent) {
            Result deferred = base(context, startedAt, finishedAt)
                    .setOutcome(CallOutcome.CALL_OUTCOME_DEFERRED)
                    .setRetryAfterMs(1_000)
                    .setError(redactor.redactError(notSent.detail(), rendered.secrets()))
                    .build();
            protections.complete(admission, observed, false);
            publish(deferred, targetId, dispatch.getTraceParent());
            record(deferred, dispatch, processingStarted);
            return;
        }

        if (callResult == null) {
            protections.abandon(admission);
            throw new IllegalStateException("The HTTP client returned no call result");
        }
        Classification classification = OutcomeClassifier.classify(callResult, endpoint.statusRules());
        boolean success = classification instanceof Classification.Success;
        protections.complete(admission, observed, success);

        List<String> outputSecrets = new ArrayList<>(rendered.secrets());
        outputs.values().stream()
                .filter(OutputValue::sensitive)
                .map(OutputValue::value)
                .map(ExecutorExecutionAction::flatten)
                .forEach(outputSecrets::addAll);
        Result result = result(context, dispatch, observed, callResult, classification, outputs, outputSecrets);
        publish(result, targetId, dispatch.getTraceParent());
        record(result, dispatch, processingStarted);
    }

    private Map<String, OutputValue> extract(EndpointVersion endpoint, HttpCallOutcome.Received response) {
        if (endpoint.outputs().isEmpty()) return Map.of();
        JsonValue body = null;
        if (response.body().json().isPresent()) {
            try {
                body = JsonValue.of(json.readValue(response.body().bytes(), Object.class));
            } catch (Exception invalid) {
                body = null;
            }
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.headers()
                .forEach(header -> headers.computeIfAbsent(header.name(), _ -> new ArrayList<>())
                        .add(header.value()));
        return OutputExtractor.extract(endpoint.outputs(), body, headers, response.status());
    }

    private Result result(
            ExecutionContext context,
            Dispatch dispatch,
            HttpCallOutcome observed,
            CallResult callResult,
            Classification classification,
            Map<String, OutputValue> outputs,
            List<String> outputSecrets) {
        Result.Builder builder =
                base(context, observed.startedAt(), observed.finishedAt()).setSentMarker(observed.requestSent());
        int status = responseStatus(observed);
        if (status > 0) builder.setHttpStatus(status);
        if (classification instanceof Classification.Success) {
            builder.setOutcome(CallOutcome.CALL_OUTCOME_SUCCEEDED);
        } else if (classification instanceof Classification.Failure failure) {
            builder.setErrorClass(dev.kairos.proto.v1.ErrorClass.forNumber(
                    failure.errorClass().code()));
            builder.setOutcome(
                    failure.kind() == FailureKind.UNKNOWN_OUTCOME
                            ? CallOutcome.CALL_OUTCOME_UNKNOWN
                            : CallOutcome.CALL_OUTCOME_FAILED);
        }
        for (Map.Entry<String, OutputValue> entry : outputs.entrySet()) {
            if (!entry.getValue().sensitive())
                builder.putOutputs(entry.getKey(), proto(entry.getValue().value()));
        }
        builder.setSealedOutputs(ByteString.copyFrom(sealSensitiveOutputs(dispatch, outputs)));
        byte[] response = responseBytes(observed);
        builder.setResponseSnippet(redactor.snippet(response, outputSecrets));
        String detail = error(observed, callResult, classification);
        if (!detail.isEmpty()) builder.setError(redactor.redactError(detail, outputSecrets));
        long retryAfter = retryAfter(observed);
        if (retryAfter > 0) builder.setRetryAfterMs(retryAfter);
        return builder.build();
    }

    private byte[] sealSensitiveOutputs(Dispatch dispatch, Map<String, OutputValue> outputs) {
        TeamId team = new TeamId(ProtoIds.toUuid(dispatch.getTeamId()));
        RunId workflowRunId = new RunId(ProtoIds.toUuid(dispatch.getTargetId()));
        return secrets.sealOutputs(team, workflowRunId, outputs);
    }

    private Result renderFailure(ExecutionContext context, Instant started, String detail) {
        HttpCallOutcome failed =
                new HttpCallOutcome.Failed(new CallResult.RenderFailed(), detail, started, clock.instant());
        return base(context, failed.startedAt(), failed.finishedAt())
                .setOutcome(CallOutcome.CALL_OUTCOME_FAILED)
                .setErrorClass(dev.kairos.proto.v1.ErrorClass.ERROR_CLASS_RENDER_FAILED)
                .setError(redactor.redactError(detail, List.of()))
                .setSentMarker(false)
                .build();
    }

    private Result.Builder base(ExecutionContext context, Instant started, Instant finished) {
        Dispatch dispatch = context.dispatch();
        return Result.newBuilder()
                .setRunId(dispatch.getRunId())
                .setShard(dispatch.getShard())
                .setFence(context.claimedFence().orElseThrow().value())
                .setAttempt(dispatch.getAttempt())
                .setInfraAttempt(dispatch.getInfraAttempt())
                .setExecutorId(executorId)
                .setStartedAt(timestamp(started))
                .setFinishedAt(timestamp(finished))
                .setLatencyMs(Math.toIntExact(Math.min(
                        Integer.MAX_VALUE,
                        Math.max(0, Duration.between(started, finished).toMillis()))));
    }

    private void publish(Result result, UUID targetId, String traceParent) throws Exception {
        long delayMillis = 250;
        while (true) {
            try {
                publisher.publish(result, targetId, traceParent);
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception failure) {
                meters.counter("kairos.executor.result.publish_failure").increment();
                TimeUnit.MILLISECONDS.sleep(delayMillis);
                delayMillis = Math.min(delayMillis * 2, 30_000);
            }
        }
    }

    private void record(Result result, Dispatch dispatch, Instant started) {
        String outcome = result.getOutcome().name().toLowerCase();
        String errorClass =
                result.getErrorClass().name().replace("ERROR_CLASS_", "").toLowerCase();
        String priority = Integer.toString(dispatch.getPriority());
        meters.counter(
                        "kairos.executor.calls_total",
                        "outcome",
                        outcome,
                        "error_class",
                        errorClass,
                        "priority",
                        priority)
                .increment();
        Timer.builder("kairos.executor.call_duration_seconds")
                .tag("tier", dispatch.getPriority() >= 8 ? "high" : dispatch.getPriority() >= 5 ? "medium" : "low")
                .register(meters)
                .record(Math.max(0, result.getLatencyMs()), TimeUnit.MILLISECONDS);
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static int responseStatus(HttpCallOutcome outcome) {
        return switch (outcome) {
            case HttpCallOutcome.Received received -> received.status();
            case HttpCallOutcome.TooLarge tooLarge -> tooLarge.status();
            case HttpCallOutcome.Failed failed ->
                switch (failed.result()) {
                    case CallResult.Responded responded -> responded.status();
                    case CallResult.ResponseTooLarge tooLarge -> tooLarge.status();
                    case CallResult.OutputMissing missing -> missing.status();
                    default -> 0;
                };
            default -> 0;
        };
    }

    private static byte[] responseBytes(HttpCallOutcome outcome) {
        return outcome instanceof HttpCallOutcome.Received received
                ? received.body().bytes()
                : new byte[0];
    }

    private static long retryAfter(HttpCallOutcome outcome) {
        List<dev.kairos.http.request.HeaderField> headers = switch (outcome) {
            case HttpCallOutcome.Received received -> received.headers();
            case HttpCallOutcome.TooLarge tooLarge -> tooLarge.headers();
            default -> List.of();
        };
        for (var header : headers) {
            if (!header.hasName("Retry-After")) continue;
            try {
                long seconds = Long.parseLong(header.value().strip());
                return seconds < 0
                        ? 0
                        : Math.min(
                                Duration.ofDays(1).toMillis(),
                                Duration.ofSeconds(seconds).toMillis());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String error(HttpCallOutcome outcome, CallResult callResult, Classification classification) {
        if (outcome instanceof HttpCallOutcome.Failed failed) return failed.detail();
        if (classification instanceof Classification.Success) return "";
        if (outcome instanceof HttpCallOutcome.Received received) {
            if (callResult instanceof CallResult.OutputMissing) return "A required response output is missing.";
            if (callResult instanceof CallResult.ResponseTooLarge) return "The response body exceeded the size limit.";
            return "The endpoint returned HTTP " + received.status() + ".";
        }
        if (outcome instanceof HttpCallOutcome.TooLarge) return "The response body exceeded the size limit.";
        return "The endpoint call failed.";
    }

    private static List<String> flatten(JsonValue value) {
        return switch (value) {
            case JsonValue.TextValue text -> List.of(text.value());
            case JsonValue.NumberValue number -> List.of(number.value().toPlainString());
            case JsonValue.BooleanValue bool -> List.of(Boolean.toString(bool.value()));
            case JsonValue.NullValue ignored -> List.of("null");
            case JsonValue.ArrayValue array ->
                array.values().stream()
                        .flatMap(element -> flatten(element).stream())
                        .toList();
            case JsonValue.ObjectValue object ->
                object.values().values().stream()
                        .flatMap(element -> flatten(element).stream())
                        .toList();
        };
    }

    private static Value proto(JsonValue json) {
        return switch (json) {
            case JsonValue.NullValue ignored ->
                Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
            case JsonValue.BooleanValue bool ->
                Value.newBuilder().setBoolValue(bool.value()).build();
            case JsonValue.NumberValue number ->
                Value.newBuilder().setNumberValue(number.value().doubleValue()).build();
            case JsonValue.TextValue text ->
                Value.newBuilder().setStringValue(text.value()).build();
            case JsonValue.ArrayValue array -> {
                ListValue.Builder list = ListValue.newBuilder();
                array.values().forEach(value -> list.addValues(proto(value)));
                yield Value.newBuilder().setListValue(list).build();
            }
            case JsonValue.ObjectValue object -> {
                Struct.Builder struct = Struct.newBuilder();
                object.values().forEach((key, value) -> struct.putFields(key, proto(value)));
                yield Value.newBuilder().setStructValue(struct).build();
            }
        };
    }
}
