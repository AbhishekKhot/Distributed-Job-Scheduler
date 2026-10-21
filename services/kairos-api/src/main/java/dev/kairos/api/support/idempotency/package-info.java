/**
 * Idempotent creates (LLD section 3.1, {@code api_idempotency} in section 2.2): an operation marked
 * {@link dev.kairos.api.support.idempotency.Idempotent} that receives an {@code Idempotency-Key} runs at most once per
 * team and key within 24 hours. A repeat with the same body gets the stored response; a repeat with a different body
 * gets 409 {@code idempotency-key-reused}; a concurrent repeat waits briefly for the first request to finish. Records
 * live in the database that owns the resource.
 */
@NullMarked
package dev.kairos.api.support.idempotency;

import org.jspecify.annotations.NullMarked;
