package dev.kairos.state.settle.application;

import dev.kairos.domain.model.ErrorClass;
import dev.kairos.domain.model.Outcome;
import dev.kairos.domain.retry.Classification;
import dev.kairos.domain.retry.FailedAttempt;
import dev.kairos.domain.retry.FailureKind;
import dev.kairos.domain.retry.OutcomeClassifier;
import dev.kairos.domain.retry.RetryDecision;
import dev.kairos.domain.retry.RetryPlanner;
import dev.kairos.engine.run.InflightRun;
import dev.kairos.proto.v1.CallOutcome;
import dev.kairos.proto.v1.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Pure outcome/retry decision; persisted run identity and counters always come from the locked row. */
public final class ResultDecider {
    private final Clock clock;
    private final RetryPlanner planner;

    public ResultDecider(Clock clock, RetryPlanner planner) {
        this.clock = clock;
        this.planner = planner;
    }

    public Decision decide(InflightRun run, boolean cancelRequested, Result result, RunPolicy policy) {
        if (cancelRequested || result.getOutcome() == CallOutcome.CALL_OUTCOME_CANCELLED)
            return new Decision(Outcome.CANCELLED, null, null, run.attempt(), false, false);
        return switch (result.getOutcome()) {
            case CALL_OUTCOME_SUCCEEDED -> new Decision(Outcome.SUCCEEDED, null, null, run.attempt(), false, false);
            case CALL_OUTCOME_SKIPPED -> new Decision(Outcome.SKIPPED, null, null, run.attempt(), false, false);
            case CALL_OUTCOME_DEFERRED ->
                new Decision(
                        null,
                        null,
                        clock.instant().plusMillis(Math.max(1000, result.getRetryAfterMs())),
                        run.attempt(),
                        true,
                        false);
            case CALL_OUTCOME_FAILED, CALL_OUTCOME_UNKNOWN -> failure(run, result, policy);
            default -> throw new IllegalArgumentException("Unsupported result outcome");
        };
    }

    private Decision failure(InflightRun run, Result result, RunPolicy policy) {
        ErrorClass error = ErrorClass.fromCode(result.getErrorClassValue());
        FailureKind kind;
        if (result.getOutcome() == CallOutcome.CALL_OUTCOME_UNKNOWN) kind = FailureKind.UNKNOWN_OUTCOME;
        else if ((error == ErrorClass.HTTP_4XX || error == ErrorClass.HTTP_429 || error == ErrorClass.HTTP_5XX)
                && result.getHttpStatus() > 0) {
            Classification classified = OutcomeClassifier.classifyStatus(result.getHttpStatus(), policy.statusRules());
            kind = classified instanceof Classification.Failure failure ? failure.kind() : FailureKind.NON_RETRYABLE;
        } else
            kind = switch (error) {
                case DNS_FAILED, CONNECT_FAILED, TLS_FAILED, TIMEOUT, HTTP_429, HTTP_5XX -> FailureKind.RETRYABLE;
                case UNKNOWN_OUTCOME -> FailureKind.UNKNOWN_OUTCOME;
                default -> FailureKind.NON_RETRYABLE;
            };
        boolean retryable = kind == FailureKind.RETRYABLE
                || kind == FailureKind.UNKNOWN_OUTCOME && policy.unknown().retries();
        if (run.attempt() == Short.MAX_VALUE)
            return new Decision(Outcome.DEAD_LETTERED, error, null, run.attempt(), false, retryable);
        RetryDecision decision = planner.plan(
                new FailedAttempt(
                        run.runId(),
                        run.attempt(),
                        run.seriesStart(),
                        run.seriesStartedAt(),
                        new Classification.Failure(error, kind),
                        result.getRetryAfterMs() == 0 ? null : Duration.ofMillis(result.getRetryAfterMs())),
                policy.retry(),
                policy.unknown());
        return switch (decision) {
            case RetryDecision.Retry retry ->
                new Decision(Outcome.FAILED_RETRYING, error, retry.dueAt(), retry.nextAttempt(), false, retryable);
            case RetryDecision.DeadLetter dead ->
                new Decision(Outcome.DEAD_LETTERED, dead.reason(), null, run.attempt(), false, retryable);
        };
    }

    public record Decision(
            @Nullable Outcome outcome,
            @Nullable ErrorClass error,
            @Nullable Instant dueAt,
            int nextAttempt,
            boolean deferred,
            boolean retryableFailure) {
        public boolean terminal() {
            return !deferred && outcome != Outcome.FAILED_RETRYING;
        }
    }
}
