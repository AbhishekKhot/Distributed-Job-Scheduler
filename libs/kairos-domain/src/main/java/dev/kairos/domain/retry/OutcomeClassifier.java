package dev.kairos.domain.retry;

import dev.kairos.domain.model.ErrorClass;
import java.util.Objects;

/**
 * Classifies what the executor observed into a {@link Classification}.
 *
 * <p>Rules, in order:
 *
 * <ol>
 *   <li>A response status is judged by the endpoint's {@code statusOverrides}, then its
 *       {@code successStatus} list, then the defaults: 429 is {@code HTTP_429} and 5xx is
 *       {@code HTTP_5XX}, both retryable; 408 and 425 are {@code HTTP_4XX} and retryable (the
 *       target asks for the request again); every other status, including an unlisted 1xx, 2xx or
 *       3xx (redirects are never followed), is {@code HTTP_4XX} and not retryable, because
 *       repeating the same request cannot change the answer.
 *   <li>A response that exceeded the size cap, or whose required output is missing, is judged by
 *       its status; if that status means success, the attempt fails as
 *       {@code RESPONSE_TOO_LARGE} or {@code OUTPUT_MISSING}, not retryable: the target has acted
 *       and would answer the same way again.
 *   <li>A timeout or connection failure after the request may have been sent is an unknown
 *       outcome ({@link FailureKind#UNKNOWN_OUTCOME}): {@code TIMEOUT} for a timeout,
 *       {@code UNKNOWN_OUTCOME} for a dropped connection. Before sending, both are
 *       {@code CONNECT_FAILED} and retryable, since the target cannot have seen the request.
 *   <li>DNS and TLS failures are retryable (nothing was sent, and both are often transient, for
 *       example during a certificate rotation). A target-policy rejection and a render failure are
 *       not retryable: they fail the same way every time.
 * </ol>
 *
 * <p>Stateless and thread-safe.
 */
public final class OutcomeClassifier {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final int REQUEST_TIMEOUT = 408;
    private static final int TOO_EARLY = 425;
    private static final int SERVER_ERROR_FROM = 500;

    private static final Classification SUCCESS = new Classification.Success();

    private OutcomeClassifier() {}

    /**
     * Classifies one observed call.
     *
     * @param result what the executor observed
     * @param rules the endpoint's status rules
     * @return the classification
     */
    public static Classification classify(CallResult result, StatusRules rules) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(rules, "rules");
        return switch (result) {
            case CallResult.Responded r -> classifyStatus(r.status(), rules);
            case CallResult.ResponseTooLarge r ->
                failIfSuccess(classifyStatus(r.status(), rules), ErrorClass.RESPONSE_TOO_LARGE);
            case CallResult.OutputMissing r ->
                failIfSuccess(classifyStatus(r.status(), rules), ErrorClass.OUTPUT_MISSING);
            case CallResult.TimedOut t ->
                t.requestSent() ? unknown(ErrorClass.TIMEOUT) : retryable(ErrorClass.CONNECT_FAILED);
            case CallResult.ConnectionFailed c ->
                c.requestSent() ? unknown(ErrorClass.UNKNOWN_OUTCOME) : retryable(ErrorClass.CONNECT_FAILED);
            case CallResult.DnsFailed _ -> retryable(ErrorClass.DNS_FAILED);
            case CallResult.TlsFailed _ -> retryable(ErrorClass.TLS_FAILED);
            case CallResult.TargetNotAllowed _ -> nonRetryable(ErrorClass.TARGET_NOT_ALLOWED);
            case CallResult.RenderFailed _ -> nonRetryable(ErrorClass.RENDER_FAILED);
        };
    }

    /**
     * Classifies an HTTP status alone.
     *
     * @param status the HTTP status, 100 to 599
     * @param rules the endpoint's status rules
     * @return the classification
     * @throws IllegalArgumentException if the status is outside 100 to 599
     */
    public static Classification classifyStatus(int status, StatusRules rules) {
        HttpStatuses.require(status);
        StatusOverride override = rules.statusOverrides().get(status);
        if (override != null) {
            return switch (override) {
                case SUCCESS -> SUCCESS;
                case RETRY -> retryable(errorClassOf(status));
                case FAIL -> nonRetryable(errorClassOf(status));
            };
        }
        if (rules.isListedAsSuccess(status)) {
            return SUCCESS;
        }
        if (status == TOO_MANY_REQUESTS || status >= SERVER_ERROR_FROM) {
            return retryable(errorClassOf(status));
        }
        if (status == REQUEST_TIMEOUT || status == TOO_EARLY) {
            return retryable(ErrorClass.HTTP_4XX);
        }
        return nonRetryable(ErrorClass.HTTP_4XX);
    }

    /**
     * Returns the error class recorded for a failed status: {@code HTTP_429}, {@code HTTP_5XX},
     * or {@code HTTP_4XX} for every other status (the catalogue has no class for unexpected 1xx,
     * 2xx or 3xx answers, which are client-side configuration problems like a 4xx).
     */
    private static ErrorClass errorClassOf(int status) {
        if (status == TOO_MANY_REQUESTS) {
            return ErrorClass.HTTP_429;
        }
        return status >= SERVER_ERROR_FROM ? ErrorClass.HTTP_5XX : ErrorClass.HTTP_4XX;
    }

    private static Classification failIfSuccess(Classification byStatus, ErrorClass errorClass) {
        return byStatus instanceof Classification.Success ? nonRetryable(errorClass) : byStatus;
    }

    private static Classification retryable(ErrorClass errorClass) {
        return new Classification.Failure(errorClass, FailureKind.RETRYABLE);
    }

    private static Classification nonRetryable(ErrorClass errorClass) {
        return new Classification.Failure(errorClass, FailureKind.NON_RETRYABLE);
    }

    private static Classification unknown(ErrorClass errorClass) {
        return new Classification.Failure(errorClass, FailureKind.UNKNOWN_OUTCOME);
    }
}
