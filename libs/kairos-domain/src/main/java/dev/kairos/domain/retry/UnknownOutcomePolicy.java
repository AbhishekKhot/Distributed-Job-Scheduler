package dev.kairos.domain.retry;

/**
 * What happens when a request may have reached the target but no response arrived
 * ({@code unknownOutcomePolicy} in the endpoint spec; HLD section 6, unknown outcomes).
 *
 * <p>Applies to timeouts and dropped connections after the request was sent, and to runs whose
 * executor died after writing the sent marker. Stored by name in JSON, so constants are never
 * renamed.
 */
public enum UnknownOutcomePolicy {
    /**
     * Retry with the normal retry policy. Every attempt of a run sends the run id as
     * {@code Idempotency-Key}, so the target can recognise the repeat. The default.
     */
    RETRY_WITH_IDEMPOTENCY_KEY,
    /** Dead-letter the run with reason {@code UNKNOWN_OUTCOME} so a person decides. */
    REVIEW;

    /** The default for new endpoints. */
    public static final UnknownOutcomePolicy DEFAULT = RETRY_WITH_IDEMPOTENCY_KEY;

    /**
     * Tells whether an unknown outcome may be retried under this policy.
     *
     * @return true for {@link #RETRY_WITH_IDEMPOTENCY_KEY}
     */
    public boolean retries() {
        return this == RETRY_WITH_IDEMPOTENCY_KEY;
    }
}
