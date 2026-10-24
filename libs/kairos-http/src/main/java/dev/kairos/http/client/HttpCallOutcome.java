package dev.kairos.http.client;

import dev.kairos.domain.retry.CallResult;
import dev.kairos.http.request.HeaderField;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What happened to one call. Every outcome says precisely whether the request may have reached the target
 * ({@link #requestSent()}): true once the {@link SendObserver} let the request through and its first byte may have
 * been written, false when it provably was not.
 */
public sealed interface HttpCallOutcome {

    /**
     * Returns when the call started.
     *
     * @return the start time from the injected clock
     */
    Instant startedAt();

    /**
     * Returns when the call finished.
     *
     * @return the finish time from the injected clock
     */
    Instant finishedAt();

    /**
     * Tells whether the request may have reached the target.
     *
     * @return true if any request byte may have been written
     */
    boolean requestSent();

    /**
     * Returns the input of the outcome classifier (T-103).
     *
     * @return the call result, or empty for {@link NotSent}, which is not an attempt
     */
    Optional<CallResult> callResult();

    /**
     * Tells whether a status is a valid final HTTP status.
     *
     * @param status the status
     * @return true if it is 100 to 599
     */
    static boolean isValidStatus(int status) {
        return status >= 100 && status <= 599;
    }

    private static void requireStatus(int status) {
        if (!isValidStatus(status)) {
            throw new IllegalArgumentException("an HTTP status is 100 to 599");
        }
    }

    /**
     * The target answered and the whole body was read within the cap.
     *
     * @param status the HTTP status, 100 to 599
     * @param headers the response headers (values with control characters are dropped)
     * @param body the body
     * @param startedAt when the call started
     * @param finishedAt when the body was read
     */
    record Received(int status, List<HeaderField> headers, ResponseBody body, Instant startedAt, Instant finishedAt)
            implements HttpCallOutcome {

        /** Validates and copies the components. */
        public Received {
            headers = List.copyOf(headers);
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(finishedAt, "finishedAt");
            requireStatus(status);
        }

        @Override
        public boolean requestSent() {
            return true;
        }

        @Override
        public Optional<CallResult> callResult() {
            return Optional.of(new CallResult.Responded(status));
        }

        /**
         * Returns the first value of a response header.
         *
         * @param name the header name, any case
         * @return the value, or empty if absent
         */
        public Optional<String> header(String name) {
            return headers.stream()
                    .filter(header -> header.hasName(name))
                    .map(HeaderField::value)
                    .findFirst();
        }
    }

    /**
     * The target answered, but the body exceeded 256 KB; reading stopped and the connection was discarded.
     *
     * @param status the HTTP status, 100 to 599
     * @param headers the response headers
     * @param startedAt when the call started
     * @param finishedAt when reading stopped
     */
    record TooLarge(int status, List<HeaderField> headers, Instant startedAt, Instant finishedAt)
            implements HttpCallOutcome {

        /** Validates and copies the components. */
        public TooLarge {
            headers = List.copyOf(headers);
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(finishedAt, "finishedAt");
            requireStatus(status);
        }

        @Override
        public boolean requestSent() {
            return true;
        }

        @Override
        public Optional<CallResult> callResult() {
            return Optional.of(new CallResult.ResponseTooLarge(status));
        }
    }

    /**
     * No complete response: the target was refused, DNS or TLS failed, the connection failed, or the call timed out.
     *
     * @param result the classifier input; {@link CallResult.TimedOut} and {@link CallResult.ConnectionFailed} carry
     *     whether the request was sent
     * @param detail a fixed, secret-free description
     * @param startedAt when the call started
     * @param finishedAt when it failed
     */
    record Failed(CallResult result, String detail, Instant startedAt, Instant finishedAt) implements HttpCallOutcome {

        /** Validates the components. */
        public Failed {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(finishedAt, "finishedAt");
            if (result instanceof CallResult.Responded
                    || result instanceof CallResult.ResponseTooLarge
                    || result instanceof CallResult.OutputMissing) {
                throw new IllegalArgumentException("a failed call has no response");
            }
        }

        @Override
        public boolean requestSent() {
            return switch (result) {
                case CallResult.TimedOut timedOut -> timedOut.requestSent();
                case CallResult.ConnectionFailed failed -> failed.requestSent();
                default -> false;
            };
        }

        @Override
        public Optional<CallResult> callResult() {
            return Optional.of(result);
        }
    }

    /**
     * The {@link SendObserver} stopped the request; nothing was written. Not an attempt: the run is re-queued.
     *
     * @param detail the observer's reason
     * @param startedAt when the call started
     * @param finishedAt when it was stopped
     */
    record NotSent(String detail, Instant startedAt, Instant finishedAt) implements HttpCallOutcome {

        /** Validates the components. */
        public NotSent {
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(finishedAt, "finishedAt");
        }

        @Override
        public boolean requestSent() {
            return false;
        }

        @Override
        public Optional<CallResult> callResult() {
            return Optional.empty();
        }
    }

    /**
     * The call was explicitly cancelled by the scheduler.
     *
     * @param requestSent whether the request may have reached the target
     * @param startedAt when the exchange started
     * @param finishedAt when cancellation ended the exchange
     */
    record Cancelled(boolean requestSent, Instant startedAt, Instant finishedAt) implements HttpCallOutcome {
        /** Validates the timestamps. */
        public Cancelled {
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(finishedAt, "finishedAt");
        }

        @Override
        public Optional<CallResult> callResult() {
            return Optional.empty();
        }
    }
}
