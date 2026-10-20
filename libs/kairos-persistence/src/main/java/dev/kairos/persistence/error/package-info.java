/**
 * Database error handling: {@link dev.kairos.persistence.error.SqlErrorTranslator} turns unique violations into
 * {@link dev.kairos.domain.error.ConflictException} (codes per constraint from
 * {@link dev.kairos.persistence.error.ConstraintConflicts}) and serialization failures and deadlocks into
 * {@link dev.kairos.persistence.error.RetryableDatabaseException}, which
 * {@link dev.kairos.persistence.error.TransientRetry} retries a bounded number of times with jitter.
 */
package dev.kairos.persistence.error;
