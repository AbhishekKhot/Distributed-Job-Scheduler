/**
 * Retries: policy, backoff, outcome classification and the retry or dead-letter decision.
 *
 * <p>{@link dev.kairos.domain.retry.RetryPolicy} holds the limits (5 retries, 30s base, 10-minute
 * cap and 24h maximum age by default) and is resolved job first, then endpoint, then team, then
 * platform. {@link dev.kairos.domain.retry.RetryBackoff} strategies turn a retry number into a
 * delay. {@link dev.kairos.domain.retry.OutcomeClassifier} turns what the executor observed into
 * a {@link dev.kairos.domain.retry.Classification}, and
 * {@link dev.kairos.domain.retry.RetryPlanner} combines a failure, the policy and the
 * {@link dev.kairos.domain.retry.UnknownOutcomePolicy} into a
 * {@link dev.kairos.domain.retry.RetryDecision}: retry later with the same idempotency key, or
 * dead-letter.
 */
package dev.kairos.domain.retry;
