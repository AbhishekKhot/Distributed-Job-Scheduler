package dev.kairos.domain.retry;

/** How a failed attempt may be followed up. */
public enum FailureKind {
    /** Safe to retry under the retry policy. */
    RETRYABLE,
    /** Retrying cannot help; the run is dead-lettered. */
    NON_RETRYABLE,
    /**
     * The request may have reached the target and no response arrived; the endpoint's
     * {@link UnknownOutcomePolicy} decides.
     */
    UNKNOWN_OUTCOME
}
