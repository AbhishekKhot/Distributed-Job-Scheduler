package dev.kairos.domain.retry;

/**
 * Treatment forced for one HTTP status by an endpoint's {@code statusOverrides}, for example
 * {@code "409": "SUCCESS"} for a target that answers a repeated create with 409. Stored by name in
 * JSON, so constants are never renamed.
 */
public enum StatusOverride {
    /** The attempt succeeded. */
    SUCCESS,
    /** The attempt failed and may be retried. */
    RETRY,
    /** The attempt failed and is not retried. */
    FAIL
}
