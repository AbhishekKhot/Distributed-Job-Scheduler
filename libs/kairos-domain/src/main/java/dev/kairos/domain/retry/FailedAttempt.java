package dev.kairos.domain.retry;

import dev.kairos.domain.id.RunId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A failed attempt to settle, with the run's retry-series position.
 *
 * @param runId the run, also its {@code Idempotency-Key}
 * @param attempt the attempt that failed, at least 1
 * @param seriesStart the first attempt of the current retry series ({@code series_start}), 1 to
 *     {@code attempt}
 * @param seriesStartedAt when the series started ({@code series_started_at}), the max-age anchor
 * @param failure the classified failure
 * @param retryAfter the target's {@code Retry-After} hint, or null if it sent none
 */
public record FailedAttempt(
        RunId runId,
        int attempt,
        int seriesStart,
        Instant seriesStartedAt,
        Classification.Failure failure,
        @Nullable Duration retryAfter) {

    /**
     * Validates the components.
     *
     * @throws NullPointerException if a required component is null
     * @throws IllegalArgumentException if {@code attempt < 1}, {@code seriesStart} is outside 1 to
     *     {@code attempt}, or {@code retryAfter} is negative
     */
    public FailedAttempt {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(seriesStartedAt, "seriesStartedAt");
        Objects.requireNonNull(failure, "failure");
        if (attempt < 1) {
            throw new IllegalArgumentException("Attempts start at 1");
        }
        if (seriesStart < 1 || seriesStart > attempt) {
            throw new IllegalArgumentException("The series starts at or before the failed attempt");
        }
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("Retry-After cannot be negative");
        }
    }

    /**
     * Returns the retries already made in the current series.
     *
     * @return {@code attempt - seriesStart}
     */
    public int retriesUsed() {
        return attempt - seriesStart;
    }
}
