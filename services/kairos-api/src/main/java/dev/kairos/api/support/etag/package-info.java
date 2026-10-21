/**
 * Optimistic concurrency on single resources (LLD section 3.1): {@code ETag: "<version>"} on responses, and
 * {@code If-Match} required on {@code PUT}, with 428 when it is missing and 412 when it is stale.
 */
@NullMarked
package dev.kairos.api.support.etag;

import org.jspecify.annotations.NullMarked;
